package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibraryData
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.PagedLibrary
import com.zenstream.zenstreammobile.model.PlaybackData
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SubtitleStyle
import com.zenstream.zenstreammobile.model.HomeData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface HomeDataSource {
    suspend fun clearSession()
    suspend fun homeFeatured(session: AuthSession): List<MediaItem>
    suspend fun homeContinueWatching(session: AuthSession): List<MediaItem>
    suspend fun homeNextUp(session: AuthSession): List<MediaItem>
    suspend fun homeLibraries(session: AuthSession): List<Library>
    suspend fun homeLibraryData(session: AuthSession, library: Library): LibraryData
}

interface LibraryDataSource {
    suspend fun clearSession()
    suspend fun libraries(session: AuthSession): List<Library>
    suspend fun libraryPage(
        session: AuthSession,
        library: Library,
        startIndex: Int,
        limit: Int,
        sort: LibrarySort,
    ): PagedLibrary

    suspend fun cachedLibrarySort(userId: String, libraryId: String): LibrarySort?
    suspend fun saveLibrarySort(userId: String, libraryId: String, sort: LibrarySort)
}

interface SearchDataSource {
    suspend fun clearSession()
    suspend fun search(session: AuthSession, query: String): List<MediaItem>
}

class CatalogRepository(
    private val api: CatalogApi,
    private val sessionStore: SessionStore,
    private val orchestratorApi: OrchestratorApi = OrchestratorApi(),
) : HomeDataSource, LibraryDataSource, SearchDataSource {
    private val homeMutex = Mutex()
    private var homeCache: Pair<Long, HomeData>? = null
    val serverUrl: Flow<String?> = sessionStore.serverUrl
    val orchestratorUrl: Flow<String?> = sessionStore.orchestratorUrl
    val session: Flow<AuthSession?> = sessionStore.session
    val locale: Flow<String> = sessionStore.locale
	val metadataLanguage: Flow<String> = sessionStore.metadataLanguage
    val playerEngine: Flow<PlayerEngine> = sessionStore.playerEngine

    suspend fun saveServerUrl(value: String) = sessionStore.saveServerUrl(normalizeServerUrl(value))

    suspend fun configureOrchestrator(value: String) {
        val orchestrator = normalizeServerUrl(value)
        // Keep the user's server choice before the network request. If the
        // config endpoint is temporarily unavailable, the next launch can
        // keep the configured address instead of presenting a blank form.
        sessionStore.saveOrchestratorUrl(orchestrator)
        orchestratorApi.fetchConfig(orchestrator)
        sessionStore.saveServerConfig(orchestrator)
    }

    suspend fun authenticate(username: String, password: String): AuthSession {
        val server = sessionStore.currentServerUrl() ?: error("Server URL is not configured")
        return api.authenticate(server, username, password).also { sessionStore.saveSession(it) }
    }

    suspend fun refreshLocale(orchestratorUrl: String, token: String) {
        sessionStore.saveLocale(orchestratorApi.fetchLocale(orchestratorUrl, token))
		runCatching { orchestratorApi.fetchMetadataPreference(orchestratorUrl, token) }
			.onSuccess { sessionStore.saveMetadataLanguage(it.effectiveLanguage) }
    }

	suspend fun loadMetadataPreference(): MetadataPreference {
		val current = session.first() ?: error("Authentication required")
		return orchestratorApi.fetchMetadataPreference(current.serverUrl, current.token).also {
			sessionStore.saveMetadataLanguage(it.effectiveLanguage)
		}
	}

	suspend fun saveMetadataPreference(language: String?): MetadataPreference {
		val current = session.first() ?: error("Authentication required")
		return orchestratorApi.setMetadataPreference(current.serverUrl, current.token, language).also {
			sessionStore.saveMetadataLanguage(it.effectiveLanguage)
		}
	}

    override suspend fun clearSession() {
        homeMutex.withLock { homeCache = null }
        sessionStore.clearSession()
    }
    suspend fun clearAll() = sessionStore.clearAll()

    override suspend fun homeFeatured(session: AuthSession) = home(session).featured
    override suspend fun homeContinueWatching(session: AuthSession) =
        home(session).rows.firstOrNull { it.title == com.zenstream.zenstreammobile.model.RowTitle.ContinueWatching }?.items.orEmpty()

    override suspend fun homeNextUp(session: AuthSession) =
        home(session).rows.firstOrNull { it.title == com.zenstream.zenstreammobile.model.RowTitle.NextUp }?.items.orEmpty()
    override suspend fun homeLibraries(session: AuthSession) =
        api.getLibraries(session, CatalogApi.HOME_REQUEST_TIMEOUT_MILLIS)

    override suspend fun homeLibraryData(
        session: AuthSession,
        library: Library,
    ) = api.fetchLibraryData(session, library, CatalogApi.HOME_REQUEST_TIMEOUT_MILLIS)

    override suspend fun libraries(session: AuthSession) = api.getLibraries(session)
    suspend fun library(
        session: AuthSession,
        library: Library
    ) = api.fetchLibraryData(session, library)

    override suspend fun libraryPage(
        session: AuthSession,
        library: Library,
        startIndex: Int,
        limit: Int,
        sort: LibrarySort,
    ): PagedLibrary = api.fetchLibraryPage(session, library, startIndex, limit, sort)

    override suspend fun search(session: AuthSession, query: String) = api.search(session, query)

    override suspend fun cachedLibrarySort(userId: String, libraryId: String): LibrarySort? =
        sessionStore.cachedLibrarySort(userId, libraryId)

    override suspend fun saveLibrarySort(userId: String, libraryId: String, sort: LibrarySort) =
        sessionStore.cacheLibrarySort(userId, libraryId, sort)

    suspend fun detail(session: AuthSession, itemId: String, seasonId: String? = null) =
        api.detail(session, itemId, seasonId)

    suspend fun setFavorite(session: AuthSession, itemId: String, favorite: Boolean) {
        api.setFavorite(session, itemId, favorite)
        invalidateHomeCache()
    }

    suspend fun setPlayed(session: AuthSession, itemId: String, played: Boolean) {
        api.setPlayed(session, itemId, played)
        invalidateHomeCache()
    }

    suspend fun playback(
        session: AuthSession,
        itemId: String,
        options: PlaybackOptions = PlaybackOptions()
    ): PlaybackData =
        api.playback(session, itemId, options)

    suspend fun trickplay(session: AuthSession, itemId: String, sourceId: String?) =
        api.trickplay(session, itemId, sourceId)

    suspend fun subtitleWebVtt(
        session: AuthSession,
        itemId: String,
        sourceId: String?,
        streamIndex: Int,
    ): String = api.subtitleWebVtt(session, itemId, sourceId, streamIndex)

    suspend fun reportPlayback(
        session: AuthSession,
        itemId: String,
        positionSeconds: Double,
        isPaused: Boolean,
        playSessionId: String?,
		durationSeconds: Double? = null,
	) {
		api.reportPlayback(session, itemId, positionSeconds, isPaused, playSessionId, durationSeconds)
		invalidateHomeCache()
	}

    suspend fun savePlayerEngine(engine: PlayerEngine) = sessionStore.savePlayerEngine(engine)

    suspend fun loadSubtitleStyle(): SubtitleStyle =
        sessionStore.cachedSubtitleStyle() ?: DEFAULT_SUBTITLE_STYLE

    suspend fun saveSubtitleStyle(style: SubtitleStyle): SubtitleStyle {
        val normalized = normalizeSubtitleStyle(style)
        sessionStore.cacheSubtitleStyle(normalized)
        return normalized
    }
    suspend fun home(session: AuthSession): HomeData = homeMutex.withLock {
        val cached = homeCache
        if (cached != null && cached.first > System.currentTimeMillis() - 30_000) return@withLock cached.second
        api.fetchHome(session).also { homeCache = System.currentTimeMillis() to it }
    }

    private suspend fun invalidateHomeCache() {
        homeMutex.withLock { homeCache = null }
    }
}

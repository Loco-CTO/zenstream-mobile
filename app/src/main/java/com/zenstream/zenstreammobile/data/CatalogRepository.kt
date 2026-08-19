package com.zenstream.zenstreammobile.data

import android.content.ContentResolver
import android.net.Uri
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.DerivedHomeData
import com.zenstream.zenstreammobile.model.FavoriteSort
import com.zenstream.zenstreammobile.model.HomeData
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibraryData
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.PagedFavorites
import com.zenstream.zenstreammobile.model.PagedLibrary
import com.zenstream.zenstreammobile.model.PlaybackData
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SubtitleStyle
import com.zenstream.zenstreammobile.model.ViewerCommandAck
import com.zenstream.zenstreammobile.model.ViewerEnd
import com.zenstream.zenstreammobile.model.ViewerHeartbeat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface CatalogRefreshSource {
    val catalogRefreshRevision: Flow<Long>
        get() = kotlinx.coroutines.flow.emptyFlow()

    suspend fun clearSession()

    suspend fun clearSessionIfCurrent(session: AuthSession) {
        clearSession()
    }
}

interface HomeDataSource : CatalogRefreshSource {
    suspend fun clearSession()

    suspend fun homeFeatured(session: AuthSession): List<MediaItem>

    suspend fun homeContinueWatching(session: AuthSession): List<MediaItem>

    suspend fun homeNextUp(session: AuthSession): List<MediaItem>

    suspend fun homeDerived(session: AuthSession): DerivedHomeData

    suspend fun homeLibraries(session: AuthSession): List<Library>

    suspend fun homeLibraryData(session: AuthSession, library: Library): LibraryData
}

interface LibraryDataSource : CatalogRefreshSource {
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

interface SearchDataSource : CatalogRefreshSource {
    suspend fun clearSession()

    suspend fun search(session: AuthSession, query: String): List<MediaItem>
}

interface FavoritesDataSource : CatalogRefreshSource {
    suspend fun clearSession()

    suspend fun favoritesPage(
        session: AuthSession,
        startIndex: Int,
        limit: Int,
        sort: FavoriteSort,
    ): PagedFavorites

    suspend fun cachedFavoriteSort(userId: String): FavoriteSort?

    suspend fun saveFavoriteSort(userId: String, sort: FavoriteSort)
}

interface SettingsDataSource {
    val interfaceLocaleMode: Flow<InterfaceLocaleMode>
    val playerEngine: Flow<PlayerEngine>
    val showDebugIcon: Flow<Boolean>

    suspend fun loadMetadataPreference(): MetadataPreference

    suspend fun saveMetadataPreference(language: String?): MetadataPreference

    suspend fun saveInterfaceLocaleMode(mode: InterfaceLocaleMode): InterfaceLocalePreference

    suspend fun savePlayerEngine(engine: PlayerEngine)

    suspend fun saveShowDebugIcon(enabled: Boolean)

    suspend fun loadSubtitleStyle(): SubtitleStyle

    suspend fun saveSubtitleStyle(style: SubtitleStyle): SubtitleStyle

    suspend fun loadPlaybackPreference(): PlaybackPreference

    suspend fun savePlaybackPreference(
        audioLanguage: String?,
        subtitleLanguage: String?,
    ): PlaybackPreference
}

data class InterfaceLocalePreference(
    val mode: InterfaceLocaleMode,
    val locale: String,
    val metadataPreference: MetadataPreference?,
)

class CatalogRepository(
    private val api: CatalogApi,
    private val sessionStore: SessionStore,
    private val orchestratorApi: OrchestratorApi = OrchestratorApi(),
) : HomeDataSource, LibraryDataSource, SearchDataSource, FavoritesDataSource, SettingsDataSource {

    suspend fun revokeSession(session: AuthSession) = api.logout(session)

    private val homeMutex = Mutex()
    private val interfaceLocaleMutex = Mutex()
    private val playbackPreferenceMutex = Mutex()
    private var homeCache: Pair<Long, HomeData>? = null
    private var playbackPreferenceCache: Pair<Long, PlaybackPreference>? = null
    private val _catalogRefreshRevision = MutableStateFlow(0L)
    override val catalogRefreshRevision: StateFlow<Long> = _catalogRefreshRevision
    val serverUrl: Flow<String?> = sessionStore.serverUrl
    val orchestratorUrl: Flow<String?> = sessionStore.orchestratorUrl
    val session: Flow<AuthSession?> = sessionStore.session
    val locale: Flow<String> = sessionStore.locale
    override val interfaceLocaleMode: Flow<InterfaceLocaleMode> = sessionStore.interfaceLocaleMode
    val metadataLanguage: Flow<String> = sessionStore.metadataLanguage
    override val playerEngine: Flow<PlayerEngine> = sessionStore.playerEngine
    override val showDebugIcon: Flow<Boolean> = sessionStore.showDebugIcon

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
        return api.authenticate(server, username, password, sessionStore.deviceId()).also {
            sessionStore.saveSession(it)
        }
    }

    suspend fun refreshCurrentAccount(): AuthSession {
        val current = session.first() ?: error("Authentication required")
        return authenticatedCatalogRequest(current) { api.refreshAccount(current) }
            .also {
                sessionStore.saveSession(it)
            }
    }

    suspend fun uploadAvatar(
        session: AuthSession,
        resolver: ContentResolver,
        uri: Uri,
        crop: AvatarCrop,
    ): AuthSession {
        val version =
            authenticatedCatalogRequest(session) { api.uploadAvatar(session, resolver, uri, crop) }
        return session.copy(avatarVersion = version).also { sessionStore.saveSession(it) }
    }

    suspend fun removeAvatar(session: AuthSession): AuthSession {
        authenticatedCatalogRequest(session) { api.deleteAvatar(session) }
        return session.copy(avatarVersion = null).also { sessionStore.saveSession(it) }
    }

    suspend fun changePassword(
        session: AuthSession,
        currentPassword: String,
        newPassword: String,
        confirmNewPassword: String,
    ) {
        authenticatedCatalogRequest(session) {
            api.changePassword(session, currentPassword, newPassword, confirmNewPassword)
        }
    }

    suspend fun syncInterfaceLocale(current: AuthSession) = interfaceLocaleMutex.withLock {
        val mode = interfaceLocaleMode.first()
        val resolvedLocale = sessionStore.resolveInterfaceLocale(mode)
        val remoteLocale = authenticatedOrchestratorRequest(current) {
            orchestratorApi.fetchLocale(current.serverUrl, current.token)
        }
        var localeChanged = false
        if (remoteLocale != resolvedLocale) {
            val savedLocale = authenticatedOrchestratorRequest(current) {
                orchestratorApi.setLocale(current.serverUrl, current.token, resolvedLocale)
            }
            check(savedLocale == resolvedLocale) { "Orchestrator returned a different locale" }
            localeChanged = true
        }

        val previousMetadataLanguage = metadataLanguage.first()
        val metadataPreference = loadMetadataPreferenceOrNull(current)
        if (metadataPreference != null) {
            sessionStore.saveMetadataLanguage(metadataPreference.effectiveLanguage)
        }
        if (
            localeChanged ||
                metadataPreference?.effectiveLanguage != null &&
                    metadataPreference.effectiveLanguage != previousMetadataLanguage
        ) {
            invalidateCatalogMetadata()
        }
    }

    override suspend fun saveInterfaceLocaleMode(
        mode: InterfaceLocaleMode
    ): InterfaceLocalePreference = interfaceLocaleMutex.withLock {
        val current = session.first() ?: error("Authentication required")
        val resolvedLocale = sessionStore.resolveInterfaceLocale(mode)
        val savedLocale = authenticatedOrchestratorRequest(current) {
            orchestratorApi.setLocale(current.serverUrl, current.token, resolvedLocale)
        }
        check(savedLocale == resolvedLocale) { "Orchestrator returned a different locale" }
        sessionStore.saveInterfaceLocaleMode(mode)

        val metadataPreference = loadMetadataPreferenceOrNull(current)
        if (metadataPreference != null) {
            sessionStore.saveMetadataLanguage(metadataPreference.effectiveLanguage)
        }
        invalidateCatalogMetadata()
        InterfaceLocalePreference(mode, savedLocale, metadataPreference)
    }

    private suspend fun loadMetadataPreferenceOrNull(current: AuthSession): MetadataPreference? =
        try {
            authenticatedOrchestratorRequest(current) {
                orchestratorApi.fetchMetadataPreference(current.serverUrl, current.token)
            }
        } catch (error: OrchestratorException) {
            if (error.statusCode == 401) throw error
            null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    private suspend fun <T> authenticatedOrchestratorRequest(block: suspend () -> T): T =
        try {
            block()
        } catch (error: OrchestratorException) {
            if (error.statusCode == 401) clearSession()
            throw error
        }

    private suspend fun <T> authenticatedCatalogRequest(block: suspend () -> T): T =
        try {
            block()
        } catch (error: CatalogException) {
            if (error.statusCode == 401) clearSession()
            throw error
        }

    override suspend fun loadMetadataPreference(): MetadataPreference {
        val current = session.first() ?: error("Authentication required")
        return authenticatedOrchestratorRequest(current) {
                orchestratorApi.fetchMetadataPreference(current.serverUrl, current.token)
            }
            .also { sessionStore.saveMetadataLanguage(it.effectiveLanguage) }
    }

    override suspend fun saveMetadataPreference(language: String?): MetadataPreference {
        val current = session.first() ?: error("Authentication required")
        return authenticatedOrchestratorRequest(current) {
                orchestratorApi.setMetadataPreference(current.serverUrl, current.token, language)
            }
            .also {
                sessionStore.saveMetadataLanguage(it.effectiveLanguage)
                invalidateCatalogMetadata()
            }
    }

    override suspend fun clearSession() {
        SyncplaySession.clear()
        homeMutex.withLock { homeCache = null }
        playbackPreferenceMutex.withLock { playbackPreferenceCache = null }
        sessionStore.clearSession()
    }

    suspend fun clearAll() {
        SyncplaySession.clear()
        homeMutex.withLock { homeCache = null }
        playbackPreferenceMutex.withLock { playbackPreferenceCache = null }
        sessionStore.clearAll()
    }

    override suspend fun homeFeatured(session: AuthSession) = api.fetchHomeFeatured(session)

    override suspend fun homeContinueWatching(session: AuthSession) =
        api.fetchHomeContinueWatching(session)

    override suspend fun homeNextUp(session: AuthSession) = api.fetchHomeNextUp(session)

    override suspend fun homeDerived(session: AuthSession) = api.fetchHomeDerived(session)

    override suspend fun homeLibraries(session: AuthSession) =
        api.getLibraries(session, CatalogApi.HOME_REQUEST_TIMEOUT_MILLIS)

    override suspend fun homeLibraryData(session: AuthSession, library: Library) =
        api.fetchHomeLibraryData(session, library, CatalogApi.HOME_REQUEST_TIMEOUT_MILLIS)

    override suspend fun libraries(session: AuthSession) = api.getLibraries(session)

    suspend fun library(
        session: AuthSession,
        library: Library,
    ) = api.fetchLibraryData(session, library)

    override suspend fun libraryPage(
        session: AuthSession,
        library: Library,
        startIndex: Int,
        limit: Int,
        sort: LibrarySort,
    ): PagedLibrary = api.fetchLibraryPage(session, library, startIndex, limit, sort)

    override suspend fun search(session: AuthSession, query: String) = api.search(session, query)

    override suspend fun favoritesPage(
        session: AuthSession,
        startIndex: Int,
        limit: Int,
        sort: FavoriteSort,
    ) = api.fetchFavoritesPage(session, startIndex, limit, sort)

    override suspend fun cachedFavoriteSort(userId: String): FavoriteSort? =
        sessionStore.cachedFavoriteSort(userId)

    override suspend fun saveFavoriteSort(userId: String, sort: FavoriteSort) =
        sessionStore.cacheFavoriteSort(userId, sort)

    override suspend fun cachedLibrarySort(userId: String, libraryId: String): LibrarySort? =
        sessionStore.cachedLibrarySort(userId, libraryId)

    override suspend fun saveLibrarySort(userId: String, libraryId: String, sort: LibrarySort) =
        sessionStore.cacheLibrarySort(userId, libraryId, sort)

    suspend fun detail(session: AuthSession, itemId: String, seasonId: String? = null) =
        api.detail(session, itemId, seasonId)

    suspend fun setFavorite(session: AuthSession, itemId: String, favorite: Boolean) {
        api.setFavorite(session, itemId, favorite)
        invalidateCatalogState()
    }

    suspend fun setPlayed(session: AuthSession, itemId: String, played: Boolean) {
        api.setPlayed(session, itemId, played)
        invalidateHomeCache()
    }

    suspend fun playback(
        session: AuthSession,
        itemId: String,
        options: PlaybackOptions = PlaybackOptions(),
    ): PlaybackData {
        api.setDeviceId(sessionStore.deviceId())
        return api.playback(session, itemId, options)
    }

    suspend fun playbackSource(session: AuthSession, itemId: String) =
        api.playbackSource(session, itemId)

    suspend fun episodeNeighbors(session: AuthSession, item: MediaItem): EpisodeNeighbors =
        api.episodeNeighbors(session, item)

    suspend fun cancelPlaybackSession(session: AuthSession, sessionId: String) =
        api.cancelPlaybackSession(session, sessionId)

    suspend fun heartbeatPlaybackViewer(
        session: AuthSession,
        viewerSessionId: String,
        positionSeconds: Double,
        durationSeconds: Double,
        paused: Boolean,
        workerSessionId: String?,
        commandAcks: List<ViewerCommandAck> = emptyList(),
    ): ViewerHeartbeat {
        api.setDeviceId(sessionStore.deviceId())
        return api.heartbeatPlaybackViewer(
            session,
            viewerSessionId,
            positionSeconds,
            durationSeconds,
            paused,
            workerSessionId,
            commandAcks,
        )
    }

    suspend fun endPlaybackViewer(
        session: AuthSession,
        viewerSessionId: String,
    ): ViewerEnd = api.endPlaybackViewer(session, viewerSessionId)

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
        api.reportPlayback(
            session,
            itemId,
            positionSeconds,
            isPaused,
            playSessionId,
            durationSeconds,
        )
        invalidateHomeCache()
    }

    override suspend fun savePlayerEngine(engine: PlayerEngine) =
        sessionStore.savePlayerEngine(engine)

    override suspend fun saveShowDebugIcon(enabled: Boolean) =
        sessionStore.saveShowDebugIcon(enabled)

    fun syncplayManager(session: AuthSession): SyncplayManager =
        SyncplaySession.manager(session, sessionStore)

    override suspend fun loadSubtitleStyle(): SubtitleStyle =
        sessionStore.cachedSubtitleStyle() ?: DEFAULT_SUBTITLE_STYLE

    override suspend fun saveSubtitleStyle(style: SubtitleStyle): SubtitleStyle {
        val normalized = normalizeSubtitleStyle(style)
        sessionStore.cacheSubtitleStyle(normalized)
        return normalized
    }

    override suspend fun loadPlaybackPreference(): PlaybackPreference =
        playbackPreferenceMutex.withLock {
            val current = session.first() ?: error("Authentication required")
            val cached = playbackPreferenceCache
            if (cached != null && cached.first > System.currentTimeMillis() - 30_000) {
                return@withLock cached.second
            }
            authenticatedOrchestratorRequest(current) {
                    orchestratorApi.fetchPlaybackPreference(current.serverUrl, current.token)
                }
                .also { playbackPreferenceCache = System.currentTimeMillis() to it }
        }

    override suspend fun savePlaybackPreference(
        audioLanguage: String?,
        subtitleLanguage: String?,
    ): PlaybackPreference = playbackPreferenceMutex.withLock {
        val current = session.first() ?: error("Authentication required")
        authenticatedOrchestratorRequest(current) {
                orchestratorApi.setPlaybackPreference(
                    current.serverUrl,
                    current.token,
                    audioLanguage,
                    subtitleLanguage,
                )
            }
            .also { playbackPreferenceCache = System.currentTimeMillis() to it }
    }

    suspend fun home(session: AuthSession, forceRefresh: Boolean = false): HomeData =
        homeMutex.withLock {
            val cached = homeCache
            if (
                !forceRefresh &&
                    cached != null &&
                    cached.first > System.currentTimeMillis() - 30_000
            ) {
                return@withLock cached.second
            }
            api.fetchHome(session).also { homeCache = System.currentTimeMillis() to it }
        }

    private suspend fun invalidateHomeCache() {
        homeMutex.withLock { homeCache = null }
    }

    private suspend fun invalidateCatalogState() {
        homeMutex.withLock {
            homeCache = null
            _catalogRefreshRevision.value += 1
        }
        playbackPreferenceMutex.withLock { playbackPreferenceCache = null }
    }

    private suspend fun invalidateCatalogMetadata() {
        homeMutex.withLock {
            homeCache = null
            _catalogRefreshRevision.value += 1
        }
        playbackPreferenceMutex.withLock { playbackPreferenceCache = null }
    }
}

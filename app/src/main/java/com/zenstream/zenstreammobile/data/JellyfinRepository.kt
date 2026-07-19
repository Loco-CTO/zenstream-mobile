package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaSource
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibraryData
import com.zenstream.zenstreammobile.model.PlaybackData
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SubtitleStyle
import kotlinx.coroutines.flow.Flow

interface HomeDataSource {
    suspend fun clearSession()
    suspend fun homeFeatured(session: AuthSession): List<MediaItem>
    suspend fun homeContinueWatching(session: AuthSession): List<MediaItem>
    suspend fun homeNextUp(session: AuthSession): List<MediaItem>
    suspend fun homeLibraries(session: AuthSession): List<Library>
    suspend fun homeLibraryData(session: AuthSession, library: Library): LibraryData
}

class JellyfinRepository(
    private val api: JellyfinApi,
    private val sessionStore: SessionStore,
    private val orchestratorApi: OrchestratorApi = OrchestratorApi(),
) : HomeDataSource {
    val serverUrl: Flow<String?> = sessionStore.serverUrl
    val orchestratorUrl: Flow<String?> = sessionStore.orchestratorUrl
    val session: Flow<AuthSession?> = sessionStore.session
    val locale: Flow<String> = sessionStore.locale
    val playerEngine: Flow<PlayerEngine> = sessionStore.playerEngine

    suspend fun saveServerUrl(value: String) = sessionStore.saveServerUrl(normalizeServerUrl(value))

    suspend fun configureOrchestrator(value: String) {
        val orchestrator = normalizeServerUrl(value)
        // Keep the user's server choice before the network request. If the
        // config endpoint is temporarily unavailable, the next launch can
        // keep the configured address instead of presenting a blank form.
        sessionStore.saveOrchestratorUrl(orchestrator)
        val jellyfin = orchestratorApi.fetchJellyfinUrl(orchestrator)
        sessionStore.saveServerConfig(orchestrator, jellyfin)
    }

    suspend fun authenticate(username: String, password: String): AuthSession {
        val server = sessionStore.currentServerUrl() ?: error("Server URL is not configured")
        return api.authenticate(server, username, password).also { sessionStore.saveSession(it) }
    }

    suspend fun refreshLocale(orchestratorUrl: String, token: String) {
        sessionStore.saveLocale(orchestratorApi.fetchLocale(orchestratorUrl, token))
    }

    override suspend fun clearSession() = sessionStore.clearSession()
    suspend fun clearAll() = sessionStore.clearAll()

    override suspend fun homeFeatured(session: AuthSession) = api.fetchHomeFeatured(session)
    override suspend fun homeContinueWatching(session: AuthSession) = api.fetchHomeContinueWatching(session)
    override suspend fun homeNextUp(session: AuthSession) = api.fetchHomeNextUp(session)
    override suspend fun homeLibraries(session: AuthSession) =
        api.getLibraries(session, JellyfinApi.HOME_REQUEST_TIMEOUT_MILLIS)

    override suspend fun homeLibraryData(
        session: AuthSession,
        library: Library,
    ) = api.fetchLibraryData(session, library, JellyfinApi.HOME_REQUEST_TIMEOUT_MILLIS)

    suspend fun libraries(session: AuthSession) = api.getLibraries(session)
    suspend fun library(
        session: AuthSession,
        library: com.zenstream.zenstreammobile.model.Library
    ) = api.fetchLibraryData(session, library)

    suspend fun search(session: AuthSession, query: String) = api.search(session, query)
    suspend fun detail(session: AuthSession, itemId: String, seasonId: String? = null) =
        api.detail(session, itemId, seasonId)

    suspend fun setFavorite(session: AuthSession, itemId: String, favorite: Boolean) =
        api.setFavorite(session, itemId, favorite)

    suspend fun setPlayed(session: AuthSession, itemId: String, played: Boolean) =
        api.setPlayed(session, itemId, played)

    suspend fun playback(session: AuthSession, itemId: String, options: PlaybackOptions = PlaybackOptions()): PlaybackData =
        api.playback(session, itemId, options)

    suspend fun subtitleWebVtt(
        session: AuthSession,
        itemId: String,
        sourceId: String?,
        streamIndex: Int,
        startPositionTicks: Long = 0L,
    ): String = api.subtitleWebVtt(session, itemId, sourceId, streamIndex, startPositionTicks)

    suspend fun reportPlayback(session: AuthSession, itemId: String, positionSeconds: Double, isPaused: Boolean) =
        api.reportPlayback(session, itemId, positionSeconds, isPaused)

    suspend fun savePlayerEngine(engine: PlayerEngine) = sessionStore.savePlayerEngine(engine)

    suspend fun loadSubtitleStyle(session: AuthSession, orchestratorUrl: String?): SubtitleStyle {
        val cached = sessionStore.cachedSubtitleStyle(session.userId)
        if (orchestratorUrl.isNullOrBlank()) return cached ?: DEFAULT_SUBTITLE_STYLE
        return runCatching { orchestratorApi.fetchSubtitleStyle(orchestratorUrl, session.token) }
            .onSuccess { sessionStore.cacheSubtitleStyle(session.userId, it) }
            .getOrElse { cached ?: DEFAULT_SUBTITLE_STYLE }
    }

    suspend fun saveSubtitleStyle(session: AuthSession, orchestratorUrl: String?, style: SubtitleStyle): SubtitleStyle {
        val normalized = normalizeSubtitleStyle(style)
        sessionStore.cacheSubtitleStyle(session.userId, normalized)
        if (orchestratorUrl.isNullOrBlank()) return normalized
        return runCatching { orchestratorApi.saveSubtitleStyle(orchestratorUrl, session.token, normalized) }
            .onSuccess { sessionStore.cacheSubtitleStyle(session.userId, it) }
            .getOrElse { normalized }
    }
}

package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession
import kotlinx.coroutines.flow.Flow

class JellyfinRepository(
    private val api: JellyfinApi,
    private val sessionStore: SessionStore,
    private val orchestratorApi: OrchestratorApi = OrchestratorApi(),
) {
    val serverUrl: Flow<String?> = sessionStore.serverUrl
    val orchestratorUrl: Flow<String?> = sessionStore.orchestratorUrl
    val session: Flow<AuthSession?> = sessionStore.session
    val locale: Flow<String> = sessionStore.locale

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

    suspend fun clearSession() = sessionStore.clearSession()
    suspend fun clearAll() = sessionStore.clearAll()

    suspend fun home(session: AuthSession) = api.fetchHome(session)
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
}

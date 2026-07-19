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
}

package com.zenstream.zenstreammobile.offline

import android.content.Context
import com.zenstream.zenstreammobile.data.CatalogApi
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OfflineRuntime private constructor(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val connection = ConnectionCoordinator(context)
    val database = OfflineDatabase.get(context)
    val sessionStore = SessionStore(context)
    val storage =
        OfflineStorage(
            database,
            java.io.File(context.filesDir, "offline"),
            scope,
            limitBytes = { connection.downloadLimitBytes.value },
        )
    private val network = CatalogRepository(CatalogApi(), sessionStore)
    val catalog =
        OfflineCatalogRepository(database) { session, path, method, body ->
            check(connection.state.value.mode != ConnectionMode.OFFLINE) {
                "Offline mode is enabled."
            }
            network.offlineRequest(session, path, method, body)
        }
    val downloads =
        DownloadCoordinator(
            catalog,
            storage,
            request = { session, path, method, body ->
                network.offlineRequest(session, path, method, body)
            },
            binary = { session, path, headers, receive ->
                network.offlineBinaryRequest(session, path, headers, receive)
            },
            activeSession = { sessionStore.session.first() },
        )

    init {
        scope.launch { sessionStore.serverUrl.collect { connection.serverUrl = it } }
    }

    companion object {
        const val ENABLED = false
        @Volatile private var instance: OfflineRuntime? = null

        fun get(context: Context): OfflineRuntime =
            instance
                ?: synchronized(this) {
                    instance ?: OfflineRuntime(context.applicationContext).also { instance = it }
                }
    }
}

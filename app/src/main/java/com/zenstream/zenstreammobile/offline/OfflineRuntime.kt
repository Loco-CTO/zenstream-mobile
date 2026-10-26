package com.zenstream.zenstreammobile.offline

import android.content.Context
import com.zenstream.zenstreammobile.data.CatalogApi
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OfflineRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext
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
        if (ENABLED)
            scope.launch {
                connection.state
                    .distinctUntilChangedBy { it.mode to it.profile }
                    .collect { policy ->
                        if (!policy.initialized) return@collect
                        if (policy.mode == ConnectionMode.OFFLINE) {
                            DownloadExecution.stop(appContext)
                            DownloadExecution.cancelReconciliation(appContext)
                        } else if (policy.profile != null)
                            DownloadExecution.scheduleReconciliation(appContext)
                    }
            }
    }

    var reconcileAccount:
        (suspend (OfflineProfile, com.zenstream.zenstreammobile.model.AuthSession) -> Unit)? =
        null
    var reconcileMetadata:
        (suspend (OfflineProfile, com.zenstream.zenstreammobile.model.AuthSession) -> Unit)? =
        null

    suspend fun reconcileBounded() {
        val policy = connection.state.first { it.initialized }
        if (policy.mode == ConnectionMode.OFFLINE) return
        val id = policy.profile ?: return
        val profile = database.offline().profile(id)?.takeUnless { it.locked } ?: return
        val cursor = connection.maintenanceCursor(id)
        connection.saveMaintenanceCursor(id, storage.repairPage(id, cursor) ?: 0)
        val bucket = connection.maintenanceCursor("$id:orphan-bucket")
        repeat(8) { step ->
            val selected = (bucket + step) % 256
            val key = "$id:orphan-offset:$selected"
            val offset = connection.maintenanceCursor(key)
            connection.saveMaintenanceCursor(key, storage.sweepBucket(id, selected, offset) ?: 0)
        }
        connection.saveMaintenanceCursor("$id:orphan-bucket", (bucket + 8) % 256)
        val session = sessionStore.session.first() ?: return
        if (profile.account != session.userId || profile.serverUrl != session.serverUrl) return
        reconcileAccount?.invoke(profile, session)
        reconcileMetadata?.invoke(profile, session)
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

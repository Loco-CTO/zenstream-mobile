package com.zenstream.zenstreammobile.data

import android.util.Log
import com.zenstream.zenstreammobile.model.AuthSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

data class CatalogInvalidation(
    val libraryId: String? = null,
    val rootEntityId: String? = null,
    val generation: Long? = null,
)

fun CatalogInvalidation.affectsLibrary(libraryId: String?): Boolean =
    this.libraryId == null || libraryId == null || this.libraryId == libraryId

fun CatalogInvalidation.affectsDetail(
    libraryId: String?,
    rootEntityId: String?,
    itemId: String,
): Boolean =
    affectsLibrary(libraryId) &&
        (this.rootEntityId == null ||
            this.rootEntityId == rootEntityId ||
            this.rootEntityId == itemId)

internal fun parseCatalogInvalidations(message: String): List<CatalogInvalidation> {
    val payload = runCatching { JSONObject(message) }.getOrNull() ?: return emptyList()
    return when (payload.optString("type")) {
        "catalog.updated" ->
            listOf(
                CatalogInvalidation(
                    libraryId = payload.optString("libraryId").ifBlank { null },
                    rootEntityId = payload.optString("rootEntityId").ifBlank { null },
                    generation = payload.optLong("generation").takeIf { payload.has("generation") },
                )
            )
        "catalog.status" ->
            List(payload.optJSONArray("libraries")?.length() ?: 0) { index ->
                    payload.optJSONArray("libraries")?.optJSONObject(index) ?: JSONObject()
                }
                .mapNotNull { library ->
                library.optString("id").ifBlank { null }?.let { libraryId ->
                    CatalogInvalidation(
                        libraryId = libraryId,
                        rootEntityId = library.optString("lastRootEntityId").ifBlank { null },
                        generation =
                            library.optLong("catalogGeneration").takeIf {
                                library.has("catalogGeneration")
                            },
                    )
                }
                }
        else -> emptyList()
    }
}

internal fun catalogReconnectDelayMillis(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

internal class CatalogUpdatesManager(
    private val api: CatalogApi,
    private val session: AuthSession,
    private val onInvalidation: (CatalogInvalidation) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stopped = AtomicBoolean(false)
    private val knownGenerations = mutableMapOf<String, Long>()
    private var connectionJob: Job? = null
    private var socket: WebSocket? = null

    fun start() {
        if (connectionJob != null || stopped.get()) return
        connectionJob =
            scope.launch {
                var attempt = 0
                while (!stopped.get()) {
                    val ended = CompletableDeferred<Unit>()
                    try {
                        val ticket = api.socketTicket(session)
                        socket = api.openCatalogSocket(session, ticket, SocketEvents(ended))
                        ended.await()
                        attempt = 0
                    } catch (error: Exception) {
                        if (!stopped.get()) {
                            Log.w(
                                CATALOG_UPDATES_TAG,
                                "Catalog socket attempt failed: ${error.javaClass.simpleName}",
                            )
                        }
                    }
                    if (!stopped.get()) {
                        delay(catalogReconnectDelayMillis(attempt))
                        attempt++
                    }
                }
            }
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        socket?.close(1000, "Catalog subscription ended")
        socket = null
        connectionJob?.cancel()
        connectionJob = null
        scope.cancel()
    }

    private fun accept(invalidation: CatalogInvalidation, initialStatus: Boolean) {
        val libraryId = invalidation.libraryId
        val generation = invalidation.generation
        if (libraryId == null || generation == null) {
            if (!initialStatus) onInvalidation(invalidation)
            return
        }
        val previous = knownGenerations.put(libraryId, generation)
        if (previous != null && generation != previous) onInvalidation(invalidation)
        else if (!initialStatus && previous == null) onInvalidation(invalidation)
    }

    private inner class SocketEvents(private val ended: CompletableDeferred<Unit>) :
        WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            val type = runCatching { JSONObject(text).optString("type") }.getOrNull()
            val invalidations = parseCatalogInvalidations(text)
            if (type == "catalog.status") {
                val visibleLibraryIds = invalidations.mapNotNullTo(mutableSetOf()) { it.libraryId }
                val removedLibraryIds = knownGenerations.keys - visibleLibraryIds
                removedLibraryIds.forEach { libraryId ->
                    knownGenerations.remove(libraryId)
                    onInvalidation(CatalogInvalidation(libraryId = libraryId))
                }
            }
            invalidations.forEach { accept(it, initialStatus = type == "catalog.status") }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) socket = null
            ended.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) socket = null
            ended.complete(Unit)
        }
    }

    private companion object {
        const val CATALOG_UPDATES_TAG = "ZenStreamCatalog"
    }
}

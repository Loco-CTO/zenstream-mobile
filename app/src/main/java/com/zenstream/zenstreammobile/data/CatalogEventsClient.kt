package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class CatalogChange(
    val generation: Long,
    val libraryId: String? = null,
    val rootEntityId: String? = null,
)

internal fun catalogEvent(message: String): CatalogChange? = runCatching {
    val value = JSONObject(message)
    if (value.optString("type") != "catalog.updated" && value.optString("type") != "catalog.changed") return@runCatching null
    CatalogChange(
        generation = value.optLong("generation"),
        libraryId = value.optString("libraryId").ifBlank { null },
        rootEntityId = value.optString("rootEntityId").ifBlank { null },
    )
}.getOrNull()

internal fun catalogEventGeneration(message: String): Long? = catalogEvent(message)?.generation

internal fun catalogEvents(message: String): List<CatalogChange> = runCatching {
    val value = JSONObject(message)
    if (value.optString("type") != "catalog.status") {
        return@runCatching listOfNotNull(catalogEvent(message))
    }
    val libraries = value.optJSONArray("libraries") ?: return@runCatching emptyList()
    List(libraries.length()) { index -> libraries.optJSONObject(index) }
        .filterNotNull()
        .mapNotNull { library ->
            library.optString("id").takeIf(String::isNotBlank)?.let { libraryId ->
                CatalogChange(
                    generation = library.optLong("catalogGeneration"),
                    libraryId = libraryId,
                    rootEntityId = null,
                )
            }
        }
}.getOrDefault(emptyList())

class CatalogEventsClient(
    private val session: AuthSession,
    private val onChanged: (CatalogChange) -> Unit,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    @Volatile private var stopped = false

    init { scope.launch { connect() } }

    fun stop() {
        stopped = true
        socket?.close(1000, "Session ended")
        socket = null
    }

    private suspend fun connect() {
        if (stopped) return
        val ticket = runCatching { socketTicket() }.getOrNull()
        if (ticket == null) {
            reconnect()
            return
        }
        val base = session.serverUrl.toHttpUrl()
        val url = base.newBuilder()
            .scheme(if (base.isHttps) "wss" else "ws")
            .addPathSegments("api/ws/catalog")
            .addQueryParameter("ticket", ticket)
            .build()
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                catalogEvents(text).forEach(onChanged)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                reconnect()
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                reconnect()
            }
        })
    }

    private fun reconnect() {
        if (stopped) return
        scope.launch {
            delay(1_000)
            connect()
        }
    }

    private suspend fun socketTicket(): String = withContext(Dispatchers.IO) {
        val url = session.serverUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/auth/socket-ticket")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${session.token}")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Socket ticket failed with ${response.code}")
            JSONObject(response.body.string()).getString("ticket")
        }
    }
}

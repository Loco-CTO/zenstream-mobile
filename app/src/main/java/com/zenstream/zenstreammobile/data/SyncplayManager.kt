package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.SyncplayGroup
import com.zenstream.zenstreammobile.model.SyncplayUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.max

class SyncplayManager(
    private val session: AuthSession,
    private val sessionStore: SessionStore,
    private val api: SyncplayApi = SyncplayApi(),
    private val socketClient: OkHttpClient = OkHttpClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncplayUiState())
    val state: StateFlow<SyncplayUiState> = _state.asStateFlow()
    private var socket: WebSocket? = null
    private var stopped = false
    private var presenceSequence = 0
    private var serverOffsetSeconds = 0.0
    private var bestRttSeconds = Double.POSITIVE_INFINITY

    init { scope.launch { start() } }

    fun serverNow(): Double = System.currentTimeMillis() / 1000.0 + serverOffsetSeconds

    private suspend fun start() {
        val participantId = sessionStore.syncplayParticipantId()
        _state.value = _state.value.copy(participantId = participantId)
        runCatching { refresh() }.onFailure { _state.value = _state.value.copy(error = it.message) }
        connect()
    }

    suspend fun refresh() = mutex.withLock {
        val groups = api.groups(session, participant())
        adoptGroups(groups)
    }

    suspend fun create(): SyncplayGroup = mutex.withLock {
        api.create(session, participant()).also(::adopt)
    }
    suspend fun join(id: String): SyncplayGroup = mutex.withLock {
        val known = _state.value.groups.firstOrNull { it.id == id }
        api.join(session, participant(), id, known?.revision ?: 0).also(::adopt)
    }
    suspend fun leave() = mutex.withLock {
        val active = _state.value.active ?: return@withLock
        api.leave(session, participant(), active)
        _state.value = _state.value.copy(active = null, groups = _state.value.groups.filter { it.id != active.id })
    }
    suspend fun setControls(enabled: Boolean) = mutex.withLock {
        _state.value.active?.let { adopt(api.setControls(session, participant(), it, enabled)) }
    }
    suspend fun removeMember(userId: String) = mutex.withLock {
        _state.value.active?.let { adopt(api.removeMember(session, participant(), it, userId)) }
    }
    suspend fun setWatchingTogether(watching: Boolean) = mutex.withLock {
        _state.value.active?.let { adopt(api.participation(session, participant(), it, watching)) }
    }
    suspend fun command(action: String, position: Double, playing: Boolean, itemId: String? = null) = mutex.withLock {
        val active = _state.value.active ?: return@withLock
        try {
            adopt(api.command(session, participant(), active, action, position.coerceAtLeast(0.0), playing, itemId))
        } catch (error: SyncplayException) {
            if (error.statusCode != 409) throw error
            val latest = api.group(session, participant(), active.id)
            adopt(latest)
            adopt(api.command(session, participant(), latest, action, position.coerceAtLeast(0.0), playing, itemId))
        }
    }
    suspend fun presence(viewing: Boolean, loading: Boolean) = mutex.withLock {
        val active = _state.value.active ?: return@withLock
        presenceSequence += 1
        adopt(api.presence(session, participant(), active, viewing, loading, presenceSequence))
    }

    fun stop() {
        stopped = true
        socket?.close(1000, "Session ended")
        socket = null
        scope.coroutineContext.cancel()
    }

    private fun connect() {
        scope.launch {
            while (!stopped) {
                runCatching {
                    val ticket = api.socketTicket(session)
                    val url = "${session.serverUrl}/api/ws/syncplay?ticket=${android.net.Uri.encode(ticket)}&participantId=${android.net.Uri.encode(participant())}"
                    socket = socketClient.newWebSocket(Request.Builder().url(url.toHttpUrl()).build(), SocketEvents())
                }.onFailure { _state.value = _state.value.copy(error = it.message) }
                delay(5_000)
                while (!stopped && _state.value.connected) delay(1_000)
            }
        }
    }

    private inner class SocketEvents : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.value = _state.value.copy(connected = true, error = null)
            scope.launch { refresh() }
            syncClock(webSocket)
            scope.launch {
                while (!stopped && _state.value.connected && socket === webSocket) {
                    delay(30_000)
                    if (_state.value.connected && socket === webSocket) syncClock(webSocket)
                }
            }
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val value = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (value.optString("type")) {
                "groups" -> scope.launch { mutex.withLock { adoptGroups(value.optJSONArray("groups").toGroups()) } }
                "group" -> value.optJSONObject("group")?.let { group -> scope.launch { mutex.withLock { adopt(parseSyncplayGroup(group)) } } }
                "group-ended" -> scope.launch { mutex.withLock { end(value.optString("id"), value.optInt("revision")) } }
                "participant-replaced" -> scope.launch { mutex.withLock { end(value.optString("id"), Int.MAX_VALUE) } }
                "clock" -> updateClock(value)
            }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { _state.value = _state.value.copy(connected = false) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { _state.value = _state.value.copy(connected = false, error = t.message) }
    }

    private fun syncClock(webSocket: WebSocket) {
        val sent = System.currentTimeMillis() / 1000.0
        webSocket.send(JSONObject().put("type", "clock").put("clientSentAt", sent).toString())
    }
    private fun updateClock(value: JSONObject) {
        val received = System.currentTimeMillis() / 1000.0
        val sent = value.optDouble("clientSentAt", Double.NaN)
        val serverReceived = value.optDouble("serverReceivedAt", Double.NaN)
        val serverSent = value.optDouble("serverSentAt", Double.NaN)
        if (!sent.isFinite() || !serverReceived.isFinite() || !serverSent.isFinite()) return
        val rtt = max(0.0, received - sent - (serverSent - serverReceived))
        if (rtt <= bestRttSeconds) {
            bestRttSeconds = rtt
            serverOffsetSeconds = (serverReceived + serverSent - sent - received) / 2.0
        }
    }

    private fun adoptGroups(groups: List<SyncplayGroup>) {
        val previous = _state.value
        val active = previous.active?.let { current -> groups.firstOrNull { it.id == current.id } ?: current }
        _state.value = previous.copy(groups = groups, active = active?.takeIf { group -> group.members.any { it.participantId == participant() } })
    }
    private fun adopt(group: SyncplayGroup) {
        val previous = _state.value
        val known = previous.groups.firstOrNull { it.id == group.id }
        if (known != null && known.revision > group.revision) return
        val groups = listOf(group) + previous.groups.filter { it.id != group.id }
        val isMember = group.members.any { it.participantId == participant() }
        val active = when {
            previous.active?.id == group.id -> group.takeIf { isMember }
            isMember -> group
            else -> previous.active
        }
        _state.value = previous.copy(groups = groups, active = active)
    }
    private fun end(id: String, revision: Int) {
        val current = _state.value
        val known = current.groups.firstOrNull { it.id == id }
        if (known != null && known.revision > revision) return
        _state.value = current.copy(groups = current.groups.filter { it.id != id }, active = current.active?.takeIf { it.id != id })
    }
    private fun participant(): String = _state.value.participantId.ifBlank { error("Syncplay has not started") }
}

object SyncplaySession {
    private var current: SyncplayManager? = null
    private var token: String? = null
    @Synchronized fun manager(session: AuthSession, store: SessionStore): SyncplayManager {
        if (token != session.token) {
            current?.stop()
            current = SyncplayManager(session, store)
            token = session.token
        }
        return requireNotNull(current)
    }
    @Synchronized fun clear() { current?.stop(); current = null; token = null }
}

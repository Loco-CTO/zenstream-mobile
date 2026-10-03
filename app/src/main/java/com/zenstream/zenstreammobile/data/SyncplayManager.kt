package com.zenstream.zenstreammobile.data

import android.util.Log
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.SyncplayGroup
import com.zenstream.zenstreammobile.model.SyncplayUiState
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class SyncplayManager(
    @Volatile private var session: AuthSession,
    private val sessionStore: SessionStore,
    private val api: SyncplayApi = SyncplayApi(),
    private val socketClient: OkHttpClient = syncplaySocketClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val commandMutex = Mutex()
    private val _state = MutableStateFlow(SyncplayUiState())
    val state: StateFlow<SyncplayUiState> = _state.asStateFlow()
    private val _notifications = MutableSharedFlow<SyncplayNotification>(extraBufferCapacity = 32)
    val notifications: SharedFlow<SyncplayNotification> = _notifications.asSharedFlow()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var stopped = false
    private val connectionGeneration = AtomicLong(0)
    private var connectionJob: Job? = null
    private var presenceSequence = 0L
    private val presenceSequenceReady = CompletableDeferred<Unit>()
    private var serverOffsetSeconds = 0.0
    private var bestRttSeconds = Double.POSITIVE_INFINITY
    private var connectionEnded: CompletableDeferred<Unit>? = null
    private var hydrated = false
    private val endedRevisions = mutableMapOf<String, Int>()
    private val stateNotificationDeduper = SyncplayNotificationDeduper()
    private val presenceLock = Any()
    private var pendingPresence: PresenceReport? = null
    private val pendingCriticalPresence = ArrayDeque<PresenceReport>()
    private var presenceWorker: Job? = null
    private var lastPresenceIntent: PresenceReport? = null
    private var activePresence: PresenceReport? = null
    private val presenceGeneration = AtomicLong(0)
    private var recoveryJob: Job? = null

    init {
        scope.launch { start() }
    }

    fun updateSession(updated: AuthSession): Boolean {
        if (updated.serverUrl != session.serverUrl || updated.userId != session.userId) return false
        val changed = session.token != updated.token
        session = updated
        if (changed && _state.value.participantId.isNotBlank()) requestRecovery()
        return true
    }

    fun serverNow(): Double = System.currentTimeMillis() / 1000.0 + serverOffsetSeconds

    private suspend fun start() {
        val participantId = sessionStore.syncplayParticipantId()
        _state.value = _state.value.copy(participantId = participantId)
        try {
            presenceSequence = sessionStore.syncplayPresenceSequence()
            presenceSequenceReady.complete(Unit)
        } catch (error: Exception) {
            presenceSequenceReady.completeExceptionally(error)
            throw error
        }
        requestRecovery()
        connect()
    }

    suspend fun refresh() {
        val membership = presenceGeneration.get()
        val requested = _state.value.active
        val groups = api.groups(session, participant())
        mutex.withLock {
            if (!stopped && membership == presenceGeneration.get()) {
                adoptGroups(
                    groups,
                    emitNotifications = true,
                    authoritative = syncplaySnapshotCanRemove(requested, _state.value.active),
                )
            }
        }
    }

    private suspend fun refreshConnectionSnapshot(isCurrent: () -> Boolean) {
        val requested = _state.value.active
        val groups = api.groups(session, participant())
        mutex.withLock {
            if (!isCurrent()) return
            adoptGroups(
                groups,
                emitNotifications = false,
                authoritative = syncplaySnapshotCanRemove(requested, _state.value.active),
            )
            _state.update { it.copy(recoveryEpoch = it.recoveryEpoch + 1) }
        }
    }

    private fun requestRecovery(webSocket: WebSocket? = socket, replace: Boolean = false) {
        synchronized(presenceLock) {
            if (stopped) return
            if (replace) {
                recoveryJob?.cancel()
                recoveryJob = null
            }
            if (recoveryJob?.isActive == true) return
            val generation = connectionGeneration.get()
            val membership = presenceGeneration.get()
            fun isCurrent() =
                !stopped &&
                    presenceGeneration.get() == membership &&
                    (webSocket == null ||
                        (socket === webSocket && connectionGeneration.get() == generation))
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        var attempt = 0
                        while (isCurrent()) {
                            try {
                                refreshConnectionSnapshot(::isCurrent)
                                if (!isCurrent()) return@launch
                                yield()
                                val delivery =
                                    withTimeout(8_000) { replayLatestPresence()?.await() }
                                if (delivery == PresenceDelivery.SUPERSEDED && isCurrent()) {
                                    val intent = synchronized(presenceLock) { lastPresenceIntent }
                                    if (
                                        intent != null &&
                                            intent.isSendable(_state.value.active ?: return@launch)
                                    ) {
                                        error("Presence was not acknowledged")
                                    }
                                }
                                if (isCurrent())
                                    Log.d(
                                        SYNCPLAY_LOG_TAG,
                                        "Syncplay recovery complete generation=$generation",
                                    )
                                return@launch
                            } catch (error: CancellationException) {
                                currentCoroutineContext().ensureActive()
                                Log.d(SYNCPLAY_LOG_TAG, "Syncplay recovery request timed out")
                            } catch (error: Exception) {
                                if (
                                    error is SyncplayException &&
                                        (error.statusCode in listOf(404, 410) ||
                                            (error.statusCode == 403 &&
                                                error.message == "Join this group first."))
                                ) {
                                    mutex.withLock {
                                        _state.value.active?.let { end(it.id, Int.MAX_VALUE) }
                                    }
                                    return@launch
                                }
                                if (!syncplayFailureIsRetryable(error)) {
                                    Log.w(
                                        SYNCPLAY_LOG_TAG,
                                        "Syncplay recovery stopped: ${error.javaClass.simpleName}",
                                    )
                                    return@launch
                                }
                                Log.d(
                                    SYNCPLAY_LOG_TAG,
                                    "Syncplay recovery retry: ${error.javaClass.simpleName}",
                                )
                            }
                            delay(syncplayRecoveryRetryMillis(attempt++))
                        }
                    } finally {
                        val owner = currentCoroutineContext()[Job]
                        synchronized(presenceLock) {
                            if (recoveryJob === owner) recoveryJob = null
                        }
                    }
                }
            recoveryJob = job
            job.start()
        }
    }

    private fun replayLatestPresence(): CompletableDeferred<PresenceDelivery>? {
        val intent = synchronized(presenceLock) { lastPresenceIntent } ?: return null
        val active = _state.value.active ?: return null
        if (!intent.isSendable(active)) return null
        return queuePresence(
            intent.copy(
                immediate = true,
                sequence = 0L,
                operationId = java.util.UUID.randomUUID().toString(),
                delivery = CompletableDeferred(),
            )
        )
    }

    private fun invalidatePresence() {
        synchronized(presenceLock) {
            presenceGeneration.incrementAndGet()
            pendingPresence?.delivery?.complete(PresenceDelivery.SUPERSEDED)
            pendingCriticalPresence.forEach { it.delivery.complete(PresenceDelivery.SUPERSEDED) }
            activePresence?.delivery?.complete(PresenceDelivery.SUPERSEDED)
            pendingPresence = null
            pendingCriticalPresence.clear()
            activePresence = null
            lastPresenceIntent = null
            presenceWorker?.cancel()
            presenceWorker = null
            recoveryJob?.cancel()
            recoveryJob = null
        }
    }

    suspend fun create(): SyncplayGroup = mutex.withLock {
        invalidatePresence()
        try {
            api.create(session, participant()).also(::adopt).also {
                notify(SyncplayNotification.GroupCreated)
            }
        } catch (error: Exception) {
            notify(
                SyncplayNotification.Failure(
                    if (error is SyncplayException && error.statusCode == 409) {
                        SyncplayFailure.CREATE_ALREADY_IN_GROUP
                    } else {
                        SyncplayFailure.CREATE
                    }
                )
            )
            throw error
        }
    }

    suspend fun join(id: String): SyncplayGroup = mutex.withLock {
        invalidatePresence()
        try {
            val known = _state.value.groups.firstOrNull { it.id == id }
            api.join(session, participant(), id, known?.revision ?: 0)
                .also { endedRevisions.remove(id) }
                .also(::adopt)
                .also { group ->
                    notify(SyncplayNotification.JoinedGroup(group.name))
                }
        } catch (error: Exception) {
            notify(
                SyncplayNotification.Failure(
                    if (error is SyncplayException && error.statusCode == 409) {
                        SyncplayFailure.JOIN_MUST_LEAVE_GROUP
                    } else {
                        SyncplayFailure.JOIN
                    }
                )
            )
            throw error
        }
    }

    suspend fun leave() = mutex.withLock {
        val active = _state.value.active ?: return@withLock
        invalidatePresence()
        try {
            api.leave(session, participant(), active)
            endedRevisions[active.id] = Int.MAX_VALUE
            _state.value =
                _state.value.copy(
                    active = null,
                    groups = _state.value.groups.filter { it.id != active.id },
                )
            notify(SyncplayNotification.LeftGroup(active.name))
        } catch (error: Exception) {
            if (
                error is SyncplayException && (error.statusCode == 403 || error.statusCode == 404)
            ) {
                _state.value =
                    _state.value.copy(
                        active = null,
                        groups = _state.value.groups.filter { it.id != active.id },
                    )
                notify(SyncplayNotification.GroupEnded(active.name))
                return@withLock
            }
            requestRecovery()
            notify(SyncplayNotification.Failure(SyncplayFailure.LEAVE))
            throw error
        }
    }

    suspend fun setControls(enabled: Boolean) = mutex.withLock {
        _state.value.active?.let {
            try {
                adopt(api.setControls(session, participant(), it, enabled))
            } catch (error: Exception) {
                notify(SyncplayNotification.Failure(SyncplayFailure.SETTINGS))
                throw error
            }
        }
    }

    suspend fun removeMember(userId: String) = mutex.withLock {
        _state.value.active?.let {
            try {
                adopt(api.removeMember(session, participant(), it, userId))
            } catch (error: Exception) {
                notify(SyncplayNotification.Failure(SyncplayFailure.SETTINGS))
                throw error
            }
        }
    }

    suspend fun setWatchingTogether(watching: Boolean) = mutex.withLock {
        _state.value.active?.let { group ->
            if (!watching) invalidatePresence()
            adopt(
                group.copy(
                    members =
                        group.members.map { member ->
                            if (member.participantId == participant()) {
                                member.copy(
                                    watchingTogether = watching,
                                    viewing = false,
                                    loading = false,
                                    readyGeneration = -1,
                                )
                            } else member
                        }
                )
            )
            try {
                adopt(api.participation(session, participant(), group, watching))
            } catch (error: Exception) {
                runCatching { api.group(session, participant(), group.id) }
                    .getOrNull()
                    ?.let(::adopt)
                notify(SyncplayNotification.Failure(SyncplayFailure.PRESENCE))
                throw error
            }
        }
    }

    suspend fun command(
        action: String,
        position: Double,
        playing: Boolean,
        itemId: String? = null,
    ) = commandMutex.withLock {
        val active = mutex.withLock { _state.value.active } ?: return@withLock
        val operationId = java.util.UUID.randomUUID().toString()
        try {
            try {
                val result =
                    api.command(
                        session,
                        participant(),
                        active,
                        action,
                        position.coerceAtLeast(0.0),
                        playing,
                        itemId,
                        operationId,
                    )
                mutex.withLock { adopt(result) }
            } catch (error: SyncplayException) {
                if (error.statusCode != 409) throw error
                val latest = api.group(session, participant(), active.id)
                mutex.withLock { adopt(latest) }
                val result =
                    api.command(
                        session,
                        participant(),
                        latest,
                        action,
                        position.coerceAtLeast(0.0),
                        playing,
                        itemId,
                        operationId,
                    )
                mutex.withLock { adopt(result) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (
                syncplayFailureIsRetryable(error) ||
                    (error is SyncplayException && error.statusCode in listOf(404, 409, 410))
            ) {
                requestRecovery()
            }
            notify(SyncplayNotification.Failure(SyncplayFailure.PLAYBACK))
            throw error
        }
    }

    private suspend fun presence(report: PresenceReport): Boolean {
        val active =
            mutex.withLock { _state.value.active }?.takeIf(report::isSendable) ?: return false
        if (report.membership != presenceGeneration.get() || report.delivery.isCompleted)
            return false
        val result =
            api.presence(
                session,
                participant(),
                if (report.isCritical) active else report.room,
                report.viewing,
                report.loading,
                report.sequence,
                report.pauseRoom,
                report.operationId,
            )
        return mutex.withLock {
            if (
                report.membership != presenceGeneration.get() ||
                    report.delivery.isCompleted ||
                    _state.value.active?.let(report::isSendable) != true
            )
                return@withLock false
            adopt(result)
            if (!report.isSendable(result)) return@withLock false
            val member = result.members.firstOrNull { it.participantId == participant() }
            val expectedLoading = if (report.pauseRoom) result.itemId != null else report.loading
            member != null &&
                member.viewing == report.viewing &&
                member.loading == expectedLoading &&
                (!report.viewing ||
                    (result.itemId == report.room.itemId &&
                        result.mediaGeneration == report.room.mediaGeneration &&
                        (expectedLoading || member.readyGeneration == report.room.mediaGeneration)))
        }
    }

    fun reportPresence(
        viewing: Boolean,
        loading: Boolean,
        immediate: Boolean = false,
        pauseRoom: Boolean = false,
    ) {
        val room = _state.value.active ?: return
        val report =
            PresenceReport(
                room = room,
                viewing = viewing,
                loading = loading && viewing,
                immediate = immediate,
                sequence = 0L,
                pauseRoom = pauseRoom,
                operationId = java.util.UUID.randomUUID().toString(),
                membership = presenceGeneration.get(),
            )
        if (
            !viewing ||
                room.members.firstOrNull { it.participantId == participant() }?.watchingTogether !=
                    false
        )
            queuePresence(report)
    }

    private fun queuePresence(report: PresenceReport): CompletableDeferred<PresenceDelivery> =
        synchronized(presenceLock) {
            if (stopped || report.membership != presenceGeneration.get()) {
                report.delivery.complete(PresenceDelivery.SUPERSEDED)
                return@synchronized report.delivery
            }
            lastPresenceIntent = report
            val existing = pendingPresence ?: pendingCriticalPresence.peekLast() ?: activePresence
            if (existing != null && !existing.delivery.isCompleted && existing.sameIntent(report)) {
                return@synchronized existing.delivery
            }
            pendingPresence?.delivery?.complete(PresenceDelivery.SUPERSEDED)
            pendingPresence = null
            activePresence
                ?.takeIf { !it.isCritical }
                ?.delivery
                ?.complete(PresenceDelivery.SUPERSEDED)
            if (report.isCritical) pendingCriticalPresence.addLast(report)
            else pendingPresence = report
            startPresenceWorkerLocked()
            return@synchronized report.delivery
        }

    private fun startPresenceWorkerLocked() {
        check(Thread.holdsLock(presenceLock))
        if (presenceWorker != null || stopped) return
        val worker =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (!stopped) {
                        val next =
                            synchronized(presenceLock) {
                                val value =
                                    if (pendingCriticalPresence.isNotEmpty())
                                        pendingCriticalPresence.removeFirst()
                                    else pendingPresence.also { pendingPresence = null }
                                activePresence = value
                                value
                            } ?: break
                        if (!next.immediate) delay(if (next.loading) 750 else 300)
                        sendPresence(next)
                        synchronized(presenceLock) {
                            if (activePresence === next) activePresence = null
                        }
                    }
                } finally {
                    val owner = currentCoroutineContext()[Job]
                    synchronized(presenceLock) {
                        if (presenceWorker === owner) {
                            presenceWorker = null
                            if (pendingCriticalPresence.isNotEmpty() || pendingPresence != null)
                                startPresenceWorkerLocked()
                        }
                    }
                }
            }
        presenceWorker = worker
        worker.start()
    }

    private suspend fun sendPresence(report: PresenceReport) {
        presenceSequenceReady.await()
        val sequenced = report.copy(sequence = nextPresenceSequence())
        var attempt = 0
        while (
            !stopped &&
                report.membership == presenceGeneration.get() &&
                !report.delivery.isCompleted
        ) {
            val active = _state.value.active
            if (active == null || !report.isSendable(active)) break
            try {
                if (presence(sequenced)) {
                    report.delivery.complete(PresenceDelivery.ACKNOWLEDGED)
                    return
                }
                if (!report.delivery.isCompleted) requestRecovery()
                break
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (
                    error is SyncplayException &&
                        (error.statusCode in listOf(404, 410) ||
                            (error.statusCode == 403 && error.message == "Join this group first."))
                ) {
                    mutex.withLock { end(report.room.id, Int.MAX_VALUE) }
                    break
                }
                if (!syncplayFailureIsRetryable(error)) {
                    Log.w(
                        SYNCPLAY_LOG_TAG,
                        "Syncplay presence stopped: ${error.javaClass.simpleName}",
                    )
                    report.delivery.complete(PresenceDelivery.SUPERSEDED)
                    return
                }
                Log.d(SYNCPLAY_LOG_TAG, "Syncplay presence retry sequence=${sequenced.sequence}")
                requestRecovery()
                delay(syncplayRecoveryRetryMillis(attempt++))
            }
        }
        report.delivery.complete(PresenceDelivery.SUPERSEDED)
    }

    private suspend fun nextPresenceSequence(): Long {
        val next =
            synchronized(presenceLock) {
                ++presenceSequence
                presenceSequence
            }
        runCatching { sessionStore.recordSyncplayPresenceSequence(next) }
            .onFailure { error ->
                Log.w(
                    SYNCPLAY_LOG_TAG,
                    "Syncplay presence sequence persistence failed: ${error.javaClass.simpleName}",
                )
            }
        return next
    }

    fun stop() {
        stopped = true
        connectionGeneration.incrementAndGet()
        invalidatePresence()
        if (!presenceSequenceReady.isCompleted) presenceSequenceReady.cancel()
        socket?.close(1000, "Session ended")
        socket = null
        connectionEnded?.complete(Unit)
        connectionEnded = null
        scope.coroutineContext.cancel()
    }

    private fun connect() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            while (!stopped) {
                val generation = connectionGeneration.incrementAndGet()
                val ended = CompletableDeferred<Unit>()
                val opened = CompletableDeferred<Unit>()
                connectionEnded = ended
                try {
                    val ticket = api.socketTicket(session)
                    if (stopped || connectionGeneration.get() != generation) return@launch
                    val url =
                        "${session.serverUrl}/api/ws/syncplay?ticket=${android.net.Uri.encode(ticket)}&participantId=${android.net.Uri.encode(participant())}"
                    val webSocket =
                        socketClient.newWebSocket(
                            Request.Builder().url(url.toHttpUrl()).build(),
                            SocketEvents(ended, opened, generation),
                        )
                    socket = webSocket
                    if (stopped || connectionGeneration.get() != generation) {
                        if (socket === webSocket) socket = null
                        webSocket.close(1000, "Session ended")
                        return@launch
                    }
                    try {
                        withTimeout(10_000) { opened.await() }
                        ended.await()
                    } finally {
                        webSocket.cancel()
                        if (socket === webSocket) {
                            socket = null
                            _state.update { it.copy(connected = false) }
                        }
                    }
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (!stopped) {
                        Log.w(
                            SYNCPLAY_LOG_TAG,
                            "Syncplay socket attempt failed: ${error.javaClass.simpleName}",
                        )
                    }
                } finally {
                    if (connectionEnded === ended) connectionEnded = null
                }
                if (!stopped) delay(5_000)
            }
        }
    }

    private inner class SocketEvents(
        private val ended: CompletableDeferred<Unit>,
        private val opened: CompletableDeferred<Unit>,
        private val generation: Long,
    ) : WebSocketListener() {
        private fun isCurrent(webSocket: WebSocket): Boolean =
            !stopped && connectionGeneration.get() == generation && socket === webSocket

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket)) return
            _state.update { it.copy(connected = true, error = null) }
            opened.complete(Unit)
            Log.d(SYNCPLAY_LOG_TAG, "Syncplay socket connected")
            requestRecovery(webSocket, replace = true)
            syncClock(webSocket)
            scope.launch {
                while (!stopped && _state.value.connected && socket === webSocket) {
                    delay(30_000)
                    if (_state.value.connected && socket === webSocket) syncClock(webSocket)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent(webSocket)) return
            val value = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (value.optString("type")) {
                "groups" -> {
                    val groups = value.optJSONArray("groups").toGroups()
                    Log.d(SYNCPLAY_LOG_TAG, "Syncplay socket groups count=${groups.size}")
                    scope.launch {
                        mutex.withLock {
                            if (isCurrent(webSocket)) adoptGroups(groups, emitNotifications = false)
                        }
                    }
                }
                "group" ->
                    value.optJSONObject("group")?.let { raw ->
                        val group = parseSyncplayGroup(raw)
                        Log.d(
                            SYNCPLAY_LOG_TAG,
                            "Syncplay socket group id=${group.id} revision=${group.revision} timeline=${group.timelineRevision} state=${group.playbackState}",
                        )
                        scope.launch { mutex.withLock { if (isCurrent(webSocket)) adopt(group) } }
                    }
                "group-ended" ->
                    scope.launch {
                        mutex.withLock {
                            if (isCurrent(webSocket))
                                end(value.optString("id"), value.optInt("revision"))
                        }
                    }
                "participant-replaced" ->
                    scope.launch {
                        mutex.withLock {
                            if (!isCurrent(webSocket)) return@withLock
                            end(
                                value.optString("id"),
                                Int.MAX_VALUE,
                                SyncplayNotification.ParticipantReplaced,
                            )
                        }
                    }
                "clock" -> updateClock(value)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket && connectionGeneration.get() == generation) {
                socket = null
                _state.update { it.copy(connected = false) }
            }
            opened.completeExceptionally(IllegalStateException("Socket closed before opening"))
            ended.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket && connectionGeneration.get() == generation) {
                socket = null
                _state.update { it.copy(connected = false) }
                Log.w(SYNCPLAY_LOG_TAG, "Syncplay socket failed: ${t.javaClass.simpleName}")
            }
            opened.completeExceptionally(t)
            ended.complete(Unit)
        }
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

    private fun adoptGroups(
        groups: List<SyncplayGroup>,
        emitNotifications: Boolean,
        authoritative: Boolean = false,
    ) {
        val previous = _state.value
        val latestGroups = groups.mapNotNull { incoming ->
            if (incoming.revision <= (endedRevisions[incoming.id] ?: -1)) return@mapNotNull null
            previous.groups
                .firstOrNull { it.id == incoming.id }
                .let { known -> latestSyncplayGroup(known, incoming) } ?: incoming
        }
        if (
            authoritative &&
                previous.active != null &&
                latestGroups.none { it.id == previous.active.id }
        ) {
            endedRevisions[previous.active.id] = Int.MAX_VALUE
        }
        val active =
            previous.active
                ?.takeUnless { authoritative && latestGroups.none { group -> group.id == it.id } }
                ?.let { current ->
                    latestGroups
                        .firstOrNull { it.id == current.id }
                        .let { candidate -> latestSyncplayGroup(current, candidate) } ?: current
                }
                ?: latestGroups.firstOrNull { group ->
                    group.members.any { it.participantId == participant() }
                }
        val next = active?.takeIf { group ->
            group.members.any { it.participantId == participant() }
        }
        if (previous.active != null && previous.active.id != next?.id) invalidatePresence()
        _state.update { it.copy(groups = latestGroups, active = next) }
        if (emitNotifications) announceChanges(previous.active, next)
        hydrated = true
    }

    private fun adopt(group: SyncplayGroup) {
        val previous = _state.value
        val known = previous.groups.firstOrNull { it.id == group.id }
        val activeKnown = previous.active?.takeIf { it.id == group.id }
        if (
            group.revision <= (endedRevisions[group.id] ?: -1) ||
                !shouldAdoptSyncplayGroup(known, activeKnown, group)
        ) {
            Log.d(
                SYNCPLAY_LOG_TAG,
                "Syncplay ignored stale group id=${group.id} revision=${group.revision}",
            )
            return
        }
        endedRevisions.remove(group.id)
        Log.d(
            SYNCPLAY_LOG_TAG,
            "Syncplay adopted group id=${group.id} revision=${group.revision} timeline=${group.timelineRevision} state=${group.playbackState}",
        )
        val groups = listOf(group) + previous.groups.filter { it.id != group.id }
        val isMember = group.members.any { it.participantId == participant() }
        val active =
            when {
                previous.active?.id == group.id -> group.takeIf { isMember }
                isMember -> group
                else -> previous.active
            }
        if (previous.active != null && previous.active.id != active?.id) invalidatePresence()
        _state.update { it.copy(groups = groups, active = active) }
        announceChanges(previous.active, active)
    }

    private fun end(id: String, revision: Int, notification: SyncplayNotification? = null) {
        val current = _state.value
        val known = current.groups.firstOrNull { it.id == id }
        val knownRevision =
            maxOf(known?.revision ?: -1, current.active?.takeIf { it.id == id }?.revision ?: -1)
        if (revision <= maxOf(knownRevision, endedRevisions[id] ?: -1)) return
        endedRevisions[id] = revision
        val active = current.active?.takeIf { it.id != id }
        if (current.active?.id == id) invalidatePresence()
        _state.update {
            it.copy(groups = current.groups.filter { group -> group.id != id }, active = active)
        }
        if (hydrated && current.active?.id == id) {
            notifyOnce(
                "group:$id:$revision:ended",
                notification ?: SyncplayNotification.GroupEnded(current.active.name),
            )
        }
    }

    private fun announceChanges(previous: SyncplayGroup?, next: SyncplayGroup?) {
        if (!hydrated || previous == null) return
        if (next == null) {
            notifyOnce(
                "group:${previous.id}:${previous.revision}:ended",
                SyncplayNotification.GroupEnded(previous.name),
            )
            return
        }
        if (previous.id != next.id || next.revision <= previous.revision) return
        if (
            !previous.hostDisconnectedAt.isFiniteOrNull() &&
                next.hostDisconnectedAt.isFiniteOrNull()
        ) {
            notifyOnce(
                "group:${next.id}:${next.revision}:host-disconnected",
                SyncplayNotification.HostDisconnected,
            )
        }
        if (previous.allowViewerControls != next.allowViewerControls) {
            notifyOnce(
                "group:${next.id}:${next.revision}:viewer-controls:${next.allowViewerControls}",
                if (next.allowViewerControls) {
                    SyncplayNotification.ViewerControlsEnabled
                } else {
                    SyncplayNotification.ViewerControlsDisabled
                },
            )
        }
        val before = previous.members.associateBy { it.participantId }
        val after = next.members.associateBy { it.participantId }
        next.members
            .filter { it.participantId != participant() && it.participantId !in before }
            .forEach {
                notifyOnce(
                    "group:${next.id}:${next.revision}:member-joined:${it.participantId}",
                    SyncplayNotification.MemberJoined(it.username),
                )
            }
        previous.members
            .filter { it.participantId != participant() && it.participantId !in after }
            .forEach {
                notifyOnce(
                    "group:${next.id}:${next.revision}:member-left:${it.participantId}",
                    SyncplayNotification.MemberLeft(it.username),
                )
            }
        if (next.itemId != null && next.mediaGeneration != previous.mediaGeneration) {
            notifyOnce(
                "group:${next.id}:${next.revision}:now-playing:${next.mediaGeneration}:${next.itemId}",
                SyncplayNotification.NowPlaying(next.itemId),
            )
        }
    }

    private fun Double?.isFiniteOrNull(): Boolean = this?.isFinite() == true

    private fun notify(notification: SyncplayNotification) {
        _notifications.tryEmit(notification)
    }

    private fun notifyOnce(key: String, notification: SyncplayNotification) {
        if (stateNotificationDeduper.shouldEmit(key)) _notifications.tryEmit(notification)
    }

    private fun participant(): String =
        _state.value.participantId.ifBlank { error("Syncplay has not started") }

    private data class PresenceReport(
        val room: SyncplayGroup,
        val viewing: Boolean,
        val loading: Boolean,
        val immediate: Boolean,
        val sequence: Long,
        val pauseRoom: Boolean,
        val operationId: String,
        val membership: Long,
        val delivery: CompletableDeferred<PresenceDelivery> = CompletableDeferred(),
    ) {
        val isCritical: Boolean
            get() = syncplayPresenceReportIsCritical(viewing, pauseRoom)

        fun sameIntent(other: PresenceReport): Boolean =
            viewing == other.viewing &&
                loading == other.loading &&
                pauseRoom == other.pauseRoom &&
                syncplayPresenceReportIsCurrent(room, other.room)

        fun isSendable(active: SyncplayGroup): Boolean =
            syncplayPresenceReportCanSend(room, active, isCritical)
    }

    private enum class PresenceDelivery {
        ACKNOWLEDGED,
        SUPERSEDED,
    }
}

private const val SYNCPLAY_LOG_TAG = "ZenStreamSyncplay"

internal fun syncplayRecoveryRetryMillis(attempt: Int): Long =
    minOf(10_000L, 500L * (1L shl attempt.coerceIn(0, 5)))

internal fun syncplaySnapshotCanRemove(
    requested: SyncplayGroup?,
    current: SyncplayGroup?,
): Boolean = requested?.id == current?.id && requested?.revision == current?.revision

internal fun syncplayFailureIsRetryable(error: Exception): Boolean =
    error !is SyncplayException || error.statusCode == 429 || error.statusCode >= 500

internal fun syncplayPresenceReportIsCritical(viewing: Boolean, pauseRoom: Boolean): Boolean =
    pauseRoom || !viewing

internal fun syncplayPresenceReportIsCurrent(
    report: SyncplayGroup,
    active: SyncplayGroup,
): Boolean =
    report.id == active.id &&
        report.itemId == active.itemId &&
        report.mediaGeneration == active.mediaGeneration &&
        report.timelineRevision == active.timelineRevision

internal fun syncplayPresenceReportCanSend(
    report: SyncplayGroup,
    active: SyncplayGroup,
    lifecycle: Boolean,
): Boolean =
    report.id == active.id && (lifecycle || syncplayPresenceReportIsCurrent(report, active))

internal fun latestSyncplayGroup(
    known: SyncplayGroup?,
    incoming: SyncplayGroup?,
): SyncplayGroup? =
    when {
        incoming == null -> known
        known != null && known.revision >= incoming.revision -> known
        else -> incoming
    }

internal fun shouldAdoptSyncplayGroup(
    known: SyncplayGroup?,
    activeKnown: SyncplayGroup?,
    incoming: SyncplayGroup,
): Boolean =
    (known == null || incoming.revision > known.revision) &&
        (activeKnown == null || incoming.revision > activeKnown.revision)

internal class SyncplayNotificationDeduper(private val capacity: Int = 256) {
    private val keys = LinkedHashSet<String>()

    @Synchronized
    fun shouldEmit(key: String): Boolean {
        if (!keys.add(key)) return false
        while (keys.size > capacity) keys.remove(keys.first())
        return true
    }
}

internal fun syncplaySocketClient(): OkHttpClient =
    OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

object SyncplaySession {
    private var current: SyncplayManager? = null

    @Synchronized
    fun manager(
        session: AuthSession,
        store: SessionStore,
        api: SyncplayApi = SyncplayApi(),
    ): SyncplayManager {
        val manager = current
        if (manager == null || !manager.updateSession(session)) {
            manager?.stop()
            return SyncplayManager(session, store, api).also { current = it }
        }
        return manager
    }

    @Synchronized
    fun clear() {
        current?.stop()
        current = null
    }
}

internal suspend fun <T> runSyncplayAction(action: suspend () -> T): T? =
    try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

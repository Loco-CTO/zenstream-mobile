package com.zenstream.zenstreammobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.model.AuthSession
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncplayRecoveryTest {
    @Test
    fun participantReplacementStopsFailedPresenceAndRejectsLateMembership() = runBlocking {
        Harness().use { h ->
            val manager = h.start()
            await { manager.state.value.recoveryEpoch > 0 }
            h.presenceFailures.set(100)
            manager.reportPresence(true, false, immediate = true)
            await { h.presenceBodies.isNotEmpty() }
            h.sockets
                .first()
                .send(
                    JSONObject().put("type", "participant-replaced").put("id", "room-1").toString()
                )
            await { manager.state.value.active == null }
            delay(100)
            val reports = h.presenceBodies.size
            h.sockets
                .first()
                .send(JSONObject().put("type", "group").put("group", h.snapshot()).toString())
            delay(1_100)
            assertNull(manager.state.value.active)
            assertEquals(reports, h.presenceBodies.size)
        }
    }

    @Test
    fun snapshotFailuresRecoverOnTheSameSocketAndRoom() = runBlocking {
        Harness().use { h ->
            h.snapshotFailures.set(2)
            val manager = h.start()
            await { manager.state.value.active != null && manager.state.value.recoveryEpoch > 0 }
            manager.reportPresence(true, false, immediate = true)
            await { manager.state.value.active?.members?.first()?.readyGeneration == 1 }
            assertEquals("room-1", manager.state.value.active?.id)
            assertEquals(1, h.sockets.size)
            assertTrue(h.snapshotReads.get() >= 3)
        }
    }

    @Test
    fun failedReadyIsRetriedWithoutAnotherPlayerEvent() = runBlocking {
        Harness(pendingPlay = true).use { h ->
            val manager = h.start()
            await { manager.state.value.recoveryEpoch > 0 }
            h.presenceFailures.set(1)
            manager.reportPresence(true, false, immediate = true)
            await { manager.state.value.active?.playing == true }
            assertTrue(h.presenceBodies.size >= 2)
            assertEquals(
                h.presenceBodies[0].getString("operationId"),
                h.presenceBodies[1].getString("operationId"),
            )
            assertEquals(
                h.presenceBodies[0].getLong("presenceSequence"),
                h.presenceBodies[1].getLong("presenceSequence"),
            )
            assertEquals(1, h.effects.get())
        }
    }

    @Test
    fun acceptedPresenceWithLostResponseRetriesTheSameOperation() = runBlocking {
        Harness(pendingPlay = true).use { h ->
            val manager = h.start()
            await { manager.state.value.recoveryEpoch > 0 }
            h.loseResponse = true
            manager.reportPresence(true, false, immediate = true)
            await { h.presenceBodies.size >= 2 && manager.state.value.active?.playing == true }
            assertEquals(
                h.presenceBodies[0].getString("operationId"),
                h.presenceBodies[1].getString("operationId"),
            )
            assertEquals(
                h.presenceBodies[0].getLong("presenceSequence"),
                h.presenceBodies[1].getLong("presenceSequence"),
            )
            assertEquals(1, h.effects.get())
        }
    }

    @Test
    fun backgroundPauseIsOrderedBeforeReadinessAndStaysPaused() = runBlocking {
        Harness(pendingPlay = true).use { h ->
            val manager = h.start()
            await { manager.state.value.recoveryEpoch > 0 }
            h.presenceFailures.set(1)
            manager.reportPresence(false, false, immediate = true, pauseRoom = true)
            manager.reportPresence(true, false, immediate = true)
            await { manager.state.value.active?.timelineRevision == 2 }
            manager.reportPresence(true, false, immediate = true)
            await { h.effects.get() == 2 }
            assertTrue(h.presenceBodies[0].getBoolean("pauseRoom"))
            assertTrue(h.presenceBodies[1].getBoolean("pauseRoom"))
            assertFalse(manager.state.value.active!!.playing)
            assertFalse(manager.state.value.active!!.resumeWhenReady)
        }
    }

    @Test
    fun changedTimelineRequiresFreshReadyAndRemovalStopsRetries() = runBlocking {
        Harness().use { h ->
            val manager = h.start()
            await { manager.state.value.recoveryEpoch > 0 }
            manager.reportPresence(true, false, immediate = true)
            await { h.effects.get() == 1 }
            h.changeTimeline()
            h.sockets
                .first()
                .send(JSONObject().put("type", "group").put("group", h.snapshot()).toString())
            await { manager.state.value.active?.timelineRevision == 2 }
            val count = h.presenceBodies.size
            manager.updateSession(h.session.copy(token = "updated"))
            await { manager.state.value.recoveryEpoch >= 2 }
            delay(300)
            assertEquals(count, h.presenceBodies.size)
            manager.reportPresence(true, false, immediate = true)
            await { h.effects.get() == 2 }
            assertEquals(2, h.presenceBodies.last().getInt("timelineRevision"))
            h.removed = true
            manager.updateSession(h.session.copy(token = "latest"))
            await { manager.state.value.active == null }
            h.sockets
                .first()
                .send(JSONObject().put("type", "group").put("group", h.snapshot()).toString())
            delay(100)
            assertNull(manager.state.value.active)
            val reads = h.snapshotReads.get()
            delay(1_100)
            assertEquals(reads, h.snapshotReads.get())
        }
    }

    @Test
    fun completeRequestDeadlineIncludesBodyAndCancellationCancelsOkHttp() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val session =
                AuthSession(server.url("/").toString().trimEnd('/'), "token", "user", "Alex")
            server.enqueue(
                MockResponse().setBody("{\"groups\":[]}").setBodyDelay(30, TimeUnit.SECONDS)
            )
            val started = System.nanoTime()
            val error = runCatching {
                SyncplayApi().groups(session, "participant")
            }
                .exceptionOrNull()
            assertNotNull(error)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) in 7_000..10_000)
            val cancelled = AtomicInteger()
            val client =
                OkHttpClient.Builder()
                    .eventListener(
                        object : EventListener() {
                            override fun canceled(call: Call) {
                                cancelled.incrementAndGet()
                            }
                        }
                    )
                    .build()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val request = async(Dispatchers.IO) { SyncplayApi(client).socketTicket(session) }
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)) // first body-stalled request
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            request.cancelAndJoin()
            await { cancelled.get() == 1 }
        }
    }

    @Test
    fun socketOpeningDeadlineAbandonsTheAttemptAndDisposalStopsRetries() = runBlocking {
        Harness().use { h ->
            h.stallOpening = true
            val manager = h.start()
            await { h.socketAttempts.get() >= 2 }
            assertFalse(manager.state.value.connected)
            manager.stop()
            val attempts = h.socketAttempts.get()
            delay(5_500)
            assertEquals(attempts, h.socketAttempts.get())
        }
    }

    private suspend fun await(predicate: () -> Boolean) =
        withTimeout(22_000) {
            while (!predicate()) delay(20)
        }

    private class Harness(pendingPlay: Boolean = false) : AutoCloseable {
        val server = MockWebServer()
        val store =
            SessionStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
                dataStoreName =
                    "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_recovery_${UUID.randomUUID()}",
            )
        val participant = runBlocking { store.syncplayParticipantId() }
        val snapshotFailures = AtomicInteger()
        val presenceFailures = AtomicInteger()
        val snapshotReads = AtomicInteger()
        val socketAttempts = AtomicInteger()
        val effects = AtomicInteger()
        val presenceBodies = CopyOnWriteArrayList<JSONObject>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val closedSockets = CopyOnWriteArrayList<WebSocket>()
        @Volatile var loseResponse = false
        @Volatile var removed = false
        @Volatile var stallOpening = false
        private val operations = mutableSetOf<String>()
        private val room =
            JSONObject()
                .put("id", "room-1")
                .put("name", "Test room")
                .put("hostUserId", "user-1")
                .put("hostName", "Alex")
                .put("itemId", "movie-1")
                .put("revision", 1)
                .put("timelineRevision", 1)
                .put("mediaGeneration", 1)
                .put("playing", false)
                .put("resumeWhenReady", pendingPlay)
                .put("playbackState", "paused")
                .put(
                    "members",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("userId", "user-1")
                                .put("username", "Alex")
                                .put("participantId", participant)
                                .put("watchingTogether", true)
                                .put("viewing", false)
                                .put("loading", true)
                                .put("readyGeneration", -1)
                                .put("role", "host")
                        ),
                )
        lateinit var session: AuthSession
        private var manager: SyncplayManager? = null

        @Synchronized fun snapshot() = JSONObject(room.toString())

        @Synchronized
        fun changeTimeline() {
            room.put("timelineRevision", 2).put("revision", room.getInt("revision") + 1)
            room.getJSONArray("members").getJSONObject(0).put("readyGeneration", -1)
        }

        fun start(): SyncplayManager {
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when {
                            request.path == "/api/auth/socket-ticket" ->
                                MockResponse().setBody("{\"ticket\":\"ticket\"}")
                            request.path == "/api/syncplay/groups" -> {
                                snapshotReads.incrementAndGet()
                                if (snapshotFailures.getAndUpdate { maxOf(0, it - 1) } > 0)
                                    MockResponse().setResponseCode(503)
                                else
                                    MockResponse()
                                        .setBody(
                                            JSONObject()
                                                .put(
                                                    "groups",
                                                    if (removed) JSONArray()
                                                    else JSONArray().put(snapshot()),
                                                )
                                                .toString()
                                        )
                            }
                            request.path?.endsWith("/presence") == true -> {
                                val body = JSONObject(request.body.readUtf8())
                                presenceBodies.add(body)
                                if (presenceFailures.getAndUpdate { maxOf(0, it - 1) } > 0)
                                    MockResponse().setResponseCode(503)
                                else
                                    synchronized(this@Harness) {
                                        if (operations.add(body.getString("operationId"))) {
                                            effects.incrementAndGet()
                                            val member =
                                                room.getJSONArray("members").getJSONObject(0)
                                            member
                                                .put("viewing", body.getBoolean("viewing"))
                                                .put("loading", body.getBoolean("loading"))
                                            if (body.getBoolean("pauseRoom")) {
                                                room
                                                    .put("resumeWhenReady", false)
                                                    .put("playing", false)
                                                    .put(
                                                        "timelineRevision",
                                                        room.getInt("timelineRevision") + 1,
                                                    )
                                                    .put("pauseReason", "background")
                                                member
                                                    .put("loading", true)
                                                    .put("readyGeneration", -1)
                                            }
                                            if (
                                                body.getBoolean("viewing") &&
                                                    !body.getBoolean("loading")
                                            ) {
                                                member.put("readyGeneration", 1)
                                                if (room.getBoolean("resumeWhenReady"))
                                                    room
                                                        .put("playing", true)
                                                        .put("resumeWhenReady", false)
                                            }
                                            room.put("revision", room.getInt("revision") + 1)
                                        }
                                        if (loseResponse) {
                                            loseResponse = false
                                            MockResponse()
                                                .setSocketPolicy(
                                                    SocketPolicy.DISCONNECT_AFTER_REQUEST
                                                )
                                        } else MockResponse().setBody(snapshot().toString())
                                    }
                            }
                            request.path?.startsWith("/api/ws/syncplay") == true -> {
                                socketAttempts.incrementAndGet()
                                if (stallOpening)
                                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                                else
                                    MockResponse()
                                        .withWebSocketUpgrade(
                                            object : WebSocketListener() {
                                                override fun onOpen(
                                                    webSocket: WebSocket,
                                                    response: Response,
                                                ) {
                                                    sockets.add(webSocket)
                                                }

                                                override fun onClosing(
                                                    webSocket: WebSocket,
                                                    code: Int,
                                                    reason: String,
                                                ) {
                                                    webSocket.close(code, reason)
                                                }

                                                override fun onClosed(
                                                    webSocket: WebSocket,
                                                    code: Int,
                                                    reason: String,
                                                ) {
                                                    closedSockets.add(webSocket)
                                                }

                                                override fun onFailure(
                                                    webSocket: WebSocket,
                                                    t: Throwable,
                                                    response: Response?,
                                                ) {
                                                    closedSockets.add(webSocket)
                                                }
                                            }
                                        )
                            }
                            else -> MockResponse().setResponseCode(404)
                        }
                }
            server.start()
            session =
                AuthSession(server.url("/").toString().trimEnd('/'), "token", "user-1", "Alex")
            return SyncplayManager(session, store).also { manager = it }
        }

        override fun close() {
            manager?.stop()
            sockets.forEach { it.close(1000, "Test complete") }
            runBlocking {
                withTimeout(5_000) { while (sockets.any { it !in closedSockets }) delay(20) }
            }
            server.shutdown()
            runBlocking { store.clearAll() }
        }
    }
}

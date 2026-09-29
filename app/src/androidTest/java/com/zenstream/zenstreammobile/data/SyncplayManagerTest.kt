package com.zenstream.zenstreammobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.model.AuthSession
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncplayManagerTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun replaysLatestPresenceAfterSocketReconnectWithFreshTimeline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store =
            SessionStore(
                context,
                dataStoreName =
                    "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_syncplay_${UUID.randomUUID()}",
            )
        val participantId = store.syncplayParticipantId()
        store.recordSyncplayPresenceSequence(40L)
        val presenceBodies = CopyOnWriteArrayList<JSONObject>()
        val firstSocketOpenLatch = CountDownLatch(1)
        val secondSocketOpenLatch = CountDownLatch(1)
        val firstPresenceLatch = CountDownLatch(1)
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val closedSockets = CopyOnWriteArrayList<WebSocket>()
        val socketListener =
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sockets.add(webSocket)
                    if (sockets.size == 1) {
                        firstSocketOpenLatch.countDown()
                    } else {
                        secondSocketOpenLatch.countDown()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closedSockets.add(webSocket)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    closedSockets.add(webSocket)
                }
            }
        val payload = { groupPayload(participantId) }
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.path == "/api/auth/socket-ticket" ->
                            MockResponse().setBody("{\"ticket\":\"ticket\"}")
                        request.path == "/api/syncplay/groups" ->
                            MockResponse()
                                .setBody(
                                    JSONObject()
                                        .put("groups", JSONArray().put(payload()))
                                        .toString()
                                )
                        request.path == "/api/syncplay/groups/room-1/presence" -> {
                            presenceBodies.add(JSONObject(request.body.readUtf8()))
                            firstPresenceLatch.countDown()
                            MockResponse().setBody(payload().toString())
                        }
                        request.path?.startsWith("/api/ws/syncplay") == true ->
                            MockResponse().withWebSocketUpgrade(socketListener)
                        else -> MockResponse().setResponseCode(404)
                    }
            }

        val serverUrl = server.url("/").toString().trimEnd('/')
        val manager =
            SyncplayManager(
                AuthSession(serverUrl, "token", "user-1", "Alex"),
                store,
                socketClient = syncplaySocketClient(),
            )
        try {
            withTimeout(10_000) {
                while (manager.state.value.active == null) delay(20)
            }
            assertTrue(
                withContext(Dispatchers.IO) {
                    firstSocketOpenLatch.await(10, TimeUnit.SECONDS)
                }
            )

            manager.reportPresence(viewing = true, loading = false, immediate = true)
            assertTrue(
                withContext(Dispatchers.IO) {
                    firstPresenceLatch.await(10, TimeUnit.SECONDS)
                }
            )
            assertTrue(presenceBodies[0].getLong("presenceSequence") >= 41L)

            val initialPresenceCount = presenceBodies.size
            assertTrue(sockets.first().close(1000, "Reconnect for test"))
            assertTrue(
                withContext(Dispatchers.IO) {
                    secondSocketOpenLatch.await(12, TimeUnit.SECONDS)
                }
            )
            withTimeout(3_000) {
                while (presenceBodies.size <= initialPresenceCount) delay(20)
            }
            assertTrue(
                presenceBodies.last().getLong("presenceSequence") >
                    presenceBodies[initialPresenceCount - 1].getLong("presenceSequence")
            )
            assertTrue(server.requestCount >= 4)
        } finally {
            manager.stop()
            sockets.forEach { it.close(1000, "Test complete") }
            withTimeout(5_000) {
                while (sockets.any { socket -> !closedSockets.contains(socket) }) delay(20)
            }
            store.clearAll()
        }
    }

    @Test
    fun managerKeepsItsConnectionAndUsesRotatedCredentialsForLaterRequests() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store =
            SessionStore(
                context,
                dataStoreName =
                    "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_syncplay_refresh_${UUID.randomUUID()}",
            )
        val updatedCredentialRequest = CountDownLatch(1)
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.path == "/api/syncplay/groups" -> {
                            if (request.getHeader("Authorization") == "Bearer access-after") {
                                updatedCredentialRequest.countDown()
                            }
                            MockResponse().setBody("{\"groups\":[]}")
                        }
                        request.path == "/api/auth/socket-ticket" ->
                            MockResponse().setBody("{\"ticket\":\"ticket\"}")
                        request.path?.startsWith("/api/ws/syncplay") == true ->
                            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {})
                        else -> MockResponse().setResponseCode(404)
                    }
            }

        val serverUrl = server.url("/").toString().trimEnd('/')
        val original = AuthSession(serverUrl, "access-before", "user-1", "Alex")
        val rotated = original.copy(token = "access-after", refreshToken = "refresh-after")
        SyncplaySession.clear()
        try {
            val manager = SyncplaySession.manager(original, store)
            val retainedManager = SyncplaySession.manager(rotated, store)

            assertSame(manager, retainedManager)
            withTimeout(5_000) {
                while (retainedManager.state.value.participantId.isNullOrBlank()) delay(20)
            }
            retainedManager.refresh()
            assertTrue(
                withContext(Dispatchers.IO) {
                    updatedCredentialRequest.await(10, TimeUnit.SECONDS)
                }
            )
        } finally {
            SyncplaySession.clear()
            store.clearAll()
        }
    }

    private fun groupPayload(participantId: String): JSONObject =
        JSONObject()
            .put("id", "room-1")
            .put("name", "Alex's group")
            .put("hostUserId", "user-1")
            .put("hostName", "Alex")
            .put("allowViewerControls", false)
            .put("itemId", "movie-1")
            .put("position", 0)
            .put("playing", false)
            .put("resumeWhenReady", false)
            .put("revision", 1)
            .put("timelineRevision", 1)
            .put("mediaGeneration", 1)
            .put("playbackState", "paused")
            .put("members", JSONArray().put(memberPayload(participantId)))

    private fun memberPayload(participantId: String): JSONObject =
        JSONObject()
            .put("userId", "user-1")
            .put("participantId", participantId)
            .put("username", "Alex")
            .put("watchingTogether", true)
            .put("viewing", false)
            .put("loading", false)
            .put("readyGeneration", -1)
            .put("role", "host")
}

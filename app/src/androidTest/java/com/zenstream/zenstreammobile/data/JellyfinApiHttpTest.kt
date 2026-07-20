package com.zenstream.zenstreammobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zenstream.zenstreammobile.model.AuthSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JellyfinApiHttpTest {
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
    fun reportsProgressWithNegotiatedSessionAndAcceptsNoContent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val session = AuthSession(server.url("/").toString().trimEnd('/'), "test-token", "user-1", "Test")

        JellyfinApi(deviceId = "device-id").reportPlayback(
            session = session,
            itemId = "item-1",
            positionSeconds = 12.5,
            isPaused = true,
            playSessionId = "play-session-1",
        )

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/playback/progress", request.path)
        assertTrue(request.getHeader("Authorization").orEmpty().contains("Token=\"test-token\""))
        val payload = JSONObject(request.body.readUtf8())
        assertEquals("item-1", payload.getString("ItemId"))
        assertEquals(125_000_000L, payload.optLong("PositionTicks"))
        assertTrue(payload.optBoolean("IsPaused"))
        assertEquals("DirectStream", payload.getString("PlayMethod"))
        assertEquals("play-session-1", payload.getString("PlaySessionId"))
    }

    @Test
    fun loadsTrickplayWithAuthenticatedItemRequest() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"Trickplay":{"source-1":{"320":{"Width":320,"Interval":10000}}}}"""
            )
        )
        val session = AuthSession(server.url("/").toString().trimEnd('/'), "test-token", "user-1", "Test")

        val result = JellyfinApi(deviceId = "device-id").trickplay(session, "episode-1")

        val request = server.takeRequest()
        assertEquals("/api/content/items/episode-1/trickplay?fields=Trickplay", request.path)
        assertTrue(request.getHeader("Authorization").orEmpty().contains("Token=\"test-token\""))
        assertEquals(320, result["source-1"]?.get("320")?.width)
        assertEquals(10_000L, result["source-1"]?.get("320")?.intervalMillis)
    }
}

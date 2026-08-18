package com.zenstream.zenstreammobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zenstream.zenstreammobile.model.AuthSession
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogApiHttpTest {
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
    fun reportsProgressWithCatalogStatePatchAndAcceptsNoContent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val session =
            AuthSession(server.url("/").toString().trimEnd('/'), "test-token", "user-1", "Test")

        CatalogApi(deviceId = "device-id")
            .reportPlayback(
                session = session,
                itemId = "item-1",
                positionSeconds = 12.5,
                isPaused = true,
                playSessionId = "play-session-1",
                durationSeconds = 100.0,
            )

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/catalog/items/item-1/state", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        val payload = JSONObject(request.body.readUtf8())
        assertEquals(12.5, payload.getDouble("positionSeconds"), 0.001)
        assertEquals(100.0, payload.getDouble("durationSeconds"), 0.001)
        assertTrue(!payload.has("isPaused"))
        assertTrue(!payload.has("playSessionId"))
    }

    @Test
    fun writesWatchedAndFavoriteStateWithCatalogStatePatches() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        val session =
            AuthSession(server.url("/").toString().trimEnd('/'), "test-token", "user-1", "Test")
        val api = CatalogApi(deviceId = "device-id")

        api.setPlayed(session, "episode-1", true)
        api.setFavorite(session, "episode-1", true)

        val played = server.takeRequest()
        assertEquals("PATCH", played.method)
        assertEquals("/api/catalog/items/episode-1/state", played.path)
        assertEquals("Bearer test-token", played.getHeader("Authorization"))
        assertTrue(JSONObject(played.body.readUtf8()).getBoolean("played"))

        val favorite = server.takeRequest()
        assertEquals("PATCH", favorite.method)
        assertEquals("/api/catalog/items/episode-1/state", favorite.path)
        assertEquals("Bearer test-token", favorite.getHeader("Authorization"))
        assertTrue(JSONObject(favorite.body.readUtf8()).getBoolean("favorite"))
    }

    @Test
    fun cancelsPlaybackSessionsWithTheCanonicalDeleteEndpoint() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        val session =
            AuthSession(server.url("/").toString().trimEnd('/'), "test-token", "user-1", "Test")

        CatalogApi(deviceId = "device-id").cancelPlaybackSession(session, "session-1")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/playback/sessions/session-1", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun uploadsRawAvatarBytesWithPixelCropParameters() = runBlocking {
        server.enqueue(MockResponse().setBody(JSONObject().put("avatarVersion", "v-2").toString()))
        val session =
            AuthSession(server.url("/").toString().trimEnd('/'), "test-token", "user-1", "Test")
        val bytes = byteArrayOf(1, 2, 3, 4)

        val version =
            CatalogApi(deviceId = "device-id")
                .uploadAvatar(
                    session,
                    bytes.toRequestBody("image/png".toMediaType()),
                    "image/png",
                    AvatarCrop(cropX = 12, cropY = 18, cropSize = 240, rotation = 90),
                )

        val request = server.takeRequest()
        assertEquals("v-2", version)
        assertEquals(
            "/api/account/avatar?cropX=12&cropY=18&cropSize=240&rotation=90",
            request.path,
        )
        assertEquals("POST", request.method)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("image/png", request.getHeader("Content-Type"))
        assertEquals(bytes.toList(), request.body.readByteArray().toList())
    }

    @Test
    fun refreshesTheCurrentAccountAvatarVersion() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(
                    JSONObject()
                        .put(
                            "user",
                            JSONObject()
                                .put("id", "user-1")
                                .put("username", "Updated")
                                .put("avatarVersion", "v-3"),
                        )
                        .toString()
                )
        )
        val session =
            AuthSession(server.url("/").toString().trimEnd('/'), "test-token", "user-1", "Test")

        val refreshed = CatalogApi(deviceId = "device-id").refreshAccount(session)

        assertEquals("Updated", refreshed.username)
        assertEquals("v-3", refreshed.avatarVersion)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/auth/me", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun removesTheCurrentAvatarWithTheAccountEndpoint() = runBlocking {
        server.enqueue(
            MockResponse().setBody(JSONObject().put("avatarVersion", JSONObject.NULL).toString())
        )
        val session =
            AuthSession(server.url("/").toString().trimEnd('/'), "test-token", "user-1", "Test")

        CatalogApi(deviceId = "device-id").deleteAvatar(session)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/account/avatar", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
    }

    @Test
    fun playbackDoesNotRequestTheRemovedMarkerEndpoint() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody(
                    JSONObject()
                        .put("id", "episode-1")
                        .put("type", "episode")
                        .put("metadata", JSONObject().put("title", "Episode"))
                        .toString()
                )
        )
        server.enqueue(
            MockResponse()
                .setBody(
                    JSONObject()
                        .put("mode", "direct")
                        .put("sessionState", "ready")
                        .put("url", "/api/playback/items/episode-1/stream?access=lease")
                        .put(
                            "source",
                            JSONObject()
                                .put("id", "source-1")
                                .put("container", "mp4")
                                .put("streams", org.json.JSONArray()),
                        )
                        .toString()
                )
        )
        server.enqueue(MockResponse().setResponseCode(404))
        val session =
            AuthSession(server.url("/").toString().trimEnd('/'), "test-token", "user-1", "Test")

        val playback = CatalogApi(deviceId = "device-id").playback(session, "episode-1")

        assertEquals("direct", playback.mode)
        assertEquals("/api/catalog/items/episode-1", server.takeRequest().path)
        assertEquals("/api/playback/items/episode-1/negotiate", server.takeRequest().path)
        assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS))
    }
}

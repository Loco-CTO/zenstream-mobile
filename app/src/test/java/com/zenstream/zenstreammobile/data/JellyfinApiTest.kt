package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class JellyfinApiTest {
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
        assertEquals("/Sessions/Playing/Progress", request.path)
        assertTrue(request.getHeader("Authorization").orEmpty().contains("Token=\"test-token\""))
        assertEquals(
            JSONObject()
                .put("ItemId", "item-1")
                .put("PositionTicks", 125_000_000L)
                .put("IsPaused", true)
                .put("PlayMethod", "DirectStream")
                .put("PlaySessionId", "play-session-1")
                .toString(),
            JSONObject(request.body.readUtf8()).toString(),
        )
    }

    @Test
    fun latestItemsQueryIsAuthenticatedAndImageEnabled() {
        val query = JellyfinApi().latestItemsQuery("user-123")

        assertEquals("user-123", query["userId"])
        assertEquals("true", query["recursive"])
        assertEquals("Series,Movie", query["includeItemTypes"])
        assertEquals("true", query["enableImages"])
        assertEquals("1", query["imageTypeLimit"])
        assertEquals("Primary,Backdrop,Logo,Thumb", query["enableImageTypes"])
        assertTrue(query["fields"].orEmpty().contains("ImageTags"))
        assertTrue(query["fields"].orEmpty().contains("BackdropImageTags"))
        assertTrue(query["fields"].orEmpty().contains("UserData"))
    }

    @Test
    fun latestItemsQueryDoesNotRequestTrailers() {
        assertFalse(
            JellyfinApi().latestItemsQuery("user-123")["fields"].orEmpty()
                .contains("RemoteTrailers")
        )
    }

    @Test
    fun newlyAddedTvLibraryQueryRequestsEpisodesByDateCreated() {
        val query = JellyfinApi().newlyAddedItemsQuery("tvshows")

        assertEquals("Episode", query["includeItemTypes"])
        assertEquals("DateCreated", query["sortBy"])
        assertEquals("Descending", query["sortOrder"])
    }

    @Test
    fun newlyAddedMovieLibraryQueryStillRequestsMovies() {
        assertEquals("Movie", JellyfinApi().newlyAddedItemsQuery("movies")["includeItemTypes"])
    }

    @Test
    fun detailQueryRequestsUserStatePeopleAndStudios() {
        val query = JellyfinApi().detailItemQuery("user-123")
        assertEquals("user-123", query["userId"])
        assertEquals("true", query["enableUserData"])
        assertTrue(query["fields"].orEmpty().contains("People"))
        assertTrue(query["fields"].orEmpty().contains("Studios"))
        assertTrue(query["fields"].orEmpty().contains("PremiereDate"))
    }
}

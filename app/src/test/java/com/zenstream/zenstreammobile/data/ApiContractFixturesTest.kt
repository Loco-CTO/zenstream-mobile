package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ApiContractFixturesTest {
    @Test
    fun parsesSharedClientResponseFixtures() {
        val root = fixtureRoot()

        val catalog = operation(root, "get_api_catalog_items_by_entity_id")
        val catalogBody = responseBody(catalog)
        val media = catalogMediaItem(catalogBody)
        assertEquals(catalogBody.getString("id"), media.id)
        assertEquals(catalogBody.getJSONObject("metadata").getString("title"), media.name)

        val notification = responseBody(operation(root, "get_api_notifications"))
        val notificationPage = parseNotificationPage(notification)
        assertEquals(notification.getInt("unreadCount"), notificationPage.unreadCount)
        assertNull(notificationPage.nextCursor)
        assertEquals(notification.getJSONArray("items").length(), notificationPage.items.size)
        assertNull(notificationPage.items.first().artistId)
        assertNull(notificationPage.items.first().subtitle)
        assertNull(notificationPage.items.first().readAt)
        assertEquals(
            notification.getJSONArray("items").getJSONObject(0).getString("kind"),
            notificationPage.items.first().kind,
        )

        val calendar = responseBody(operation(root, "get_api_calendar"))
        val calendarResponse = parseCalendarResponse(calendar)
        assertEquals(calendar.getString("start"), calendarResponse.start)
        assertEquals(calendar.getString("end"), calendarResponse.end)
        assertEquals(calendar.getJSONArray("events").length(), calendarResponse.events.size)
        assertNull(calendarResponse.events.first().catalogItemId)
        assertNull(calendarResponse.events.first().catalogSeriesId)
        assertTrue(calendarResponse.events.first().followAvailable)

        val playlist = responseBody(operation(root, "get_api_account_playlists_by_playlist_id"))
        val playlistData = parsePlaylist(playlist)
        assertEquals(playlist.getString("id"), playlistData.summary.id)
        assertEquals(playlist.getInt("page"), playlistData.page)
        assertEquals(playlist.getBoolean("hasMore"), playlistData.hasMore)

        val lyrics = responseBody(operation(root, "get_api_playback_items_by_entity_id_lyrics"))
        val parsedLyrics = parseAudioLyrics(lyrics.opt("lyrics"))
        assertNotNull(parsedLyrics)
        assertEquals("embedded", parsedLyrics?.source)
        assertTrue(parsedLyrics?.timed == true)
        assertEquals("Example lyric", parsedLyrics?.lines?.first()?.text)

        val socketFixtures = JSONObject(File(root, "syncplay.json").readText())
        val groupMessage =
            socketFixtures.getJSONArray("messages").let { messages ->
                (0 until messages.length())
                    .map { messages.getJSONObject(it) }
                    .first { it.getString("name") == "group-update" }
            }
        val group = parseSyncplayGroup(groupMessage.getJSONObject("payload").getJSONObject("group"))
        assertEquals("fixture-group", group.id)
        assertFalse(group.playing)
        assertFalse(group.resumeWhenReady)
        assertEquals(7, group.revision)
    }

    @Test
    fun existingApisSendFixtureMethodPathAndBody() = runBlocking {
        val root = fixtureRoot()
        val catalog = operation(root, "get_api_catalog_items_by_entity_id")
        val catalogRequest = catalog.getJSONObject("request")
        val catalogResponse = catalog.getJSONObject("response")
        MockWebServer().use { server ->
            server.start()
            server.enqueue(jsonResponse(catalogResponse.getJSONObject("body")))
            val session = fixtureSession(server)
            val itemId =
                catalogRequest
                    .getJSONObject("parameters")
                    .getJSONObject("path")
                    .getString("entity_id")
            val item = CatalogApi(OkHttpClient()).catalogItem(session, itemId)
            val request = server.takeRequest()

            assertEquals(catalogRequest.getString("method"), request.method)
            assertEquals(catalogRequest.getString("path"), request.path)
            assertTrue(request.body.readUtf8().isEmpty())
            assertEquals("fixture-id", item.id)
            assertEquals("Fixture Item", item.name)
        }

        val password = operation(root, "post_api_account_password")
        val passwordRequest = password.getJSONObject("request")
        val passwordBody = passwordRequest.getJSONObject("body").getJSONObject("value")
        val passwordResponse = password.getJSONObject("response")
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(passwordResponse.getInt("status")))
            val session = fixtureSession(server)
            CatalogApi(OkHttpClient())
                .changePassword(
                    session = session,
                    currentPassword = passwordBody.getString("currentPassword"),
                    newPassword = passwordBody.getString("newPassword"),
                    confirmNewPassword = passwordBody.getString("confirmNewPassword"),
                )
            val request = server.takeRequest()

            assertEquals(passwordRequest.getString("method"), request.method)
            assertEquals(passwordRequest.getString("path"), request.path)
            assertJsonEquals(passwordBody, JSONObject(request.body.readUtf8()))
        }
    }

    private fun fixtureRoot(): File {
        val path = System.getenv("ZENSTREAM_API_FIXTURE_ROOT")
        assumeTrue("ZENSTREAM_API_FIXTURE_ROOT is set by contract CI", !path.isNullOrBlank())
        val root = File(requireNotNull(path))
        assumeTrue("contract fixtures are checked out", root.isDirectory)
        return root
    }

    private fun operation(root: File, operationId: String): JSONObject {
        val operations = JSONObject(File(root, "http.json").readText()).getJSONArray("operations")
        return (0 until operations.length())
            .map { operations.getJSONObject(it) }
            .first { it.getString("operationId") == operationId }
    }

    private fun responseBody(operation: JSONObject) =
        operation.getJSONObject("response").getJSONObject("body")

    private fun fixtureSession(server: MockWebServer) =
        AuthSession(
            serverUrl = server.url("/").toString().trimEnd('/'),
            token = "fixture-test-token",
            userId = "fixture-user",
            username = "Fixture User",
        )

    private fun jsonResponse(body: JSONObject) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString())

    private fun assertJsonEquals(expected: Any?, actual: Any?) {
        when (expected) {
            is JSONObject -> {
                assertTrue(actual is JSONObject)
                val actualObject = actual as JSONObject
                assertEquals(expected.length(), actualObject.length())
                expected.keys().forEach { key ->
                    assertTrue("Missing JSON key $key", actualObject.has(key))
                    assertJsonEquals(expected.get(key), actualObject.get(key))
                }
            }
            is JSONArray -> {
                assertTrue(actual is JSONArray)
                val actualArray = actual as JSONArray
                assertEquals(expected.length(), actualArray.length())
                for (index in 0 until expected.length()) {
                    assertJsonEquals(expected.get(index), actualArray.get(index))
                }
            }
            else -> assertEquals(expected, actual)
        }
    }
}

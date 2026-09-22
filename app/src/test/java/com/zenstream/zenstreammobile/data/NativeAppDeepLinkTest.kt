package com.zenstream.zenstreammobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAppDeepLinkTest {
    @Test
    fun parsesAlbumTargetAndTrackSelection() {
        val link =
            requireNotNull(
                parseNativeAppDeepLink(
                    "zenstream://open?server=https%3A%2F%2Fexample.test%2F&target=%2Falbum%2Falbum-1%3FtrackId%3Dtrack-1"
                )
            )

        assertEquals("https://example.test", link.serverUrl)
        assertEquals(NativeAppDestination.Album("album-1", "track-1"), link.destination)
    }

    @Test
    fun parsesEpisodeLinksToTheEpisodeDetail() {
        val link =
            requireNotNull(
                parseNativeAppDeepLink(
                    "zenstream://open?server=https%3A%2F%2Fexample.test&target=%2Fshow%2Fshow-1%2Fepisode%2Fepisode-2"
                )
            )

        assertEquals(NativeAppDestination.Detail("episode-2"), link.destination)
    }

    @Test
    fun unsupportedTargetsFallBackToHome() {
        val link =
            requireNotNull(
                parseNativeAppDeepLink(
                    "zenstream://open?server=https%3A%2F%2Fexample.test&target=%2Fcalendar"
                )
            )

        assertEquals(NativeAppDestination.Home, link.destination)
    }

    @Test
    fun rejectsInvalidSchemeAndServer() {
        assertNull(
            parseNativeAppDeepLink(
                "https://example.test/?server=https%3A%2F%2Fexample.test&target=%2F"
            )
        )
        assertNull(
            parseNativeAppDeepLink("zenstream://open?server=ftp%3A%2F%2Fexample.test&target=%2F")
        )
    }

    @Test
    fun comparesEquivalentServerOrigins() {
        assertTrue(sameNativeAppServer("HTTPS://Example.test/", "https://example.test:443"))
    }
}

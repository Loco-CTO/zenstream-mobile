package com.zenstream.zenstreammobile.ui.screens

import com.zenstream.zenstreammobile.model.NotificationItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationsScreenTest {
    @Test
    fun formatsTheOffsetTimestampReturnedByTheNotificationApi() {
        val raw = "2026-08-20T23:30:39.090571+00:00"

        val formatted = formatNotificationDateTime(raw)

        assertNotEquals(raw, formatted)
        assertFalse(formatted.contains("T"))
        assertFalse(formatted.contains("+00:00"))
    }

    @Test
    fun routesReleaseNotificationsToTheirAlbumAndUsesArtistOnlyAsContext() {
        val destination =
            notificationDestination(
                NotificationItem(
                    id = "n-1",
                    kind = "new_release",
                    title = "Release",
                    itemId = "album-1",
                    artistId = "artist-1",
                    createdAt = "2026-08-20T23:30:39Z",
                )
            )

        assertEquals(NotificationDestination.Album("album-1"), destination)
    }

    @Test
    fun ignoresUnknownServerNavigationTargets() {
        val destination =
            notificationDestination(
                NotificationItem(
                    id = "n-1",
                    kind = "unknown",
                    title = "Unknown",
                    itemId = "item-1",
                    navigationTarget = "/arbitrary/server/path",
                    createdAt = "2026-08-20T23:30:39Z",
                )
            )

        assertNull(destination)
    }
}

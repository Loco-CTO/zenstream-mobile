package com.zenstream.zenstreammobile.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflinePlaybackCheckpointProfileTest {
    @Test
    fun streamedPlaybackMirrorsProgressToTheActiveOfflineProfile() {
        assertEquals(
            "profile-1",
            offlinePlaybackCheckpointProfileId(
                isLocalPlayback = false,
                localSourceProfileId = null,
                activeOfflineProfileId = "profile-1",
            ),
        )
    }

    @Test
    fun localPlaybackUsesTheResolvedSourceProfile() {
        assertEquals(
            "profile-1",
            offlinePlaybackCheckpointProfileId(
                isLocalPlayback = true,
                localSourceProfileId = "profile-1",
                activeOfflineProfileId = "profile-2",
            ),
        )
        assertNull(
            offlinePlaybackCheckpointProfileId(
                isLocalPlayback = true,
                localSourceProfileId = null,
                activeOfflineProfileId = "profile-2",
            )
        )
    }

    @Test
    fun regularOnlineStreamingDoesNotCreateAnOfflineCheckpoint() {
        assertNull(
            offlinePlaybackCheckpointProfileId(
                isLocalPlayback = false,
                localSourceProfileId = null,
                activeOfflineProfileId = null,
            )
        )
    }
}

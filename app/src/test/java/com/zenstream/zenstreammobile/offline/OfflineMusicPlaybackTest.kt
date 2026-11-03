package com.zenstream.zenstreammobile.offline

import com.zenstream.zenstreammobile.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMusicPlaybackTest {
    @Test
    fun offlineMusicQueuesIncludeOnlyDownloadedAudioTracks() {
        val saved = MediaItem("saved", "Saved", type = "Audio")
        val missing = MediaItem("missing", "Missing", type = "Audio")
        val album = MediaItem("album", "Album", type = "MusicAlbum")

        assertEquals(
            listOf(saved),
            downloadedAudioTracks(listOf(saved, missing, album), setOf("saved")),
        )
    }

    @Test
    fun sameStreamedTrackIsReselectedFromItsOfflineProfile() {
        assertTrue(
            shouldReselectDownloadedAudio(
                currentTrackId = "track-1",
                requestedTrackId = "track-1",
                currentProfileId = "profile-1",
                requestedProfileId = "profile-1",
                playbackMode = "direct",
            )
        )
        assertFalse(
            shouldReselectDownloadedAudio(
                currentTrackId = "track-1",
                requestedTrackId = "track-1",
                currentProfileId = "profile-1",
                requestedProfileId = "profile-1",
                playbackMode = "local",
            )
        )
        assertFalse(
            shouldReselectDownloadedAudio(
                currentTrackId = "track-1",
                requestedTrackId = "track-1",
                currentProfileId = null,
                requestedProfileId = "profile-1",
                playbackMode = "direct",
            )
        )
    }
}

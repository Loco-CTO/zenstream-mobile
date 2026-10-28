package com.zenstream.zenstreammobile.offline

import com.zenstream.zenstreammobile.model.MediaItem
import org.junit.Assert.*
import org.junit.Test

class LocalPlaybackDataTest {
    private fun asset(descriptor: String) =
        OfflineAsset(
            "p",
            "a",
            "item",
            "video-720p",
            "source",
            "r",
            5,
            "hash",
            "video/mp4",
            descriptor,
            "path",
            state = "complete",
        )

    @Test
    fun localDataUsesOutputTrackIdentifiersAndHasNoRemoteDependencies() {
        val data =
            localPlaybackData(
                MediaItem("item", "Movie"),
                asset(
                    """{
          "durationSeconds":60,"trackManifest":[
            {"index":0,"type":"video","codec":"h264"},
            {"index":1,"sourceIndex":4,"type":"audio","codec":"aac","language":"ja","channels":2}
          ],"bundle":{"chapters":[{"startSeconds":12,"name":"Chapter"}],
            "segments":[{"type":"intro","startSeconds":1,"endSeconds":3}]}}"""
                ),
                8.0,
            )
        assertEquals(1, data.audioTracks.single().index)
        assertEquals("ja", data.audioTracks.single().language)
        assertEquals(8.0, data.startPositionSeconds, 0.0)
        assertEquals(120000000L, data.item.chapters.single().startPositionTicks)
        assertEquals(1, data.segments.size)
        assertNull(data.url)
        assertNull(data.source.url)
        assertNull(data.viewerSessionId)
        assertNull(data.sessionId)
        assertNull(data.accessExpiresIn)
        assertTrue(data.qualities.isEmpty())
    }

    @Test
    fun ambiguousTrackIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            localPlaybackData(
                MediaItem("item", "Movie"),
                asset(
                    """{"trackManifest":[
              {"index":1,"type":"video"},{"index":1,"type":"audio"}]}"""
                ),
                0.0,
            )
        }
    }
}

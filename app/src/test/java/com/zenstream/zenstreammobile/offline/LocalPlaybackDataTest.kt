package com.zenstream.zenstreammobile.offline

import com.zenstream.zenstreammobile.model.MediaItem
import java.io.File
import org.json.JSONObject
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

    @Test
    fun downloadedSubtitleUsesDisplayNameFromNameOrTitle() {
        val source =
            ResolvedPlaybackSource.Local(
                profile = OfflineProfile("p", "server", "account", "url", "Account"),
                asset = asset("""{"durationSeconds":60,"trackManifest":[]}"""),
                data = localPlaybackData(MediaItem("item", "Movie"), asset("{}"), 0.0),
                lease = OfflineFileLease(File("video.mp4")) {},
                supplements =
                    listOf(
                        LocalSupplement(
                            "subtitle:4",
                            File("subtitle-4.vtt"),
                            JSONObject("""{"name":"Signs","language":"ja"}"""),
                        ),
                        LocalSupplement(
                            "subtitle:5",
                            File("subtitle-5.vtt"),
                            JSONObject("""{"title":"Forced","language":"en"}"""),
                        ),
                    ),
                bundle = JSONObject(),
            )

        val subtitles = source.videoData().subtitles

        assertEquals(listOf("Signs", "Forced"), subtitles.map { it.displayTitle })
        assertEquals(listOf("ja", "en"), subtitles.map { it.language })
    }
}

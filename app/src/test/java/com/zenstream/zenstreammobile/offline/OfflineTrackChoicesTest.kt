package com.zenstream.zenstreammobile.offline

import com.zenstream.zenstreammobile.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineTrackChoicesTest {
    @Test
    fun originalAndConvertedTrackChoicesUseTheirOutputIdentifiers() {
        val original =
            PlaybackData(
                MediaItem("i", "Track"),
                MediaSource("s"),
                listOf(
                    MediaStream(1, "Audio", language = "eng"),
                    MediaStream(4, "Audio", language = "jpn"),
                ),
                emptyList(),
            )
        val descriptor =
            JSONObject(
                """{"downloadOptions":{"audioStreamId":4},"trackManifest":[
            {"type":"audio","index":1},{"type":"audio","index":4}]}"""
            )
        assertEquals(1, downloadedAudioOrdinal(original, descriptor))
        val converted =
            original.copy(audioTracks = listOf(MediaStream(1, "Audio", language = "jpn")))
        descriptor.put(
            "trackManifest",
            org.json.JSONArray("""[{"type":"audio","index":1,"sourceIndex":4}]"""),
        )
        assertEquals(0, downloadedAudioOrdinal(converted, descriptor))
        descriptor.put("downloadOptions", JSONObject().put("audioLanguage", "ja"))
        assertEquals(1, downloadedAudioOrdinal(original, descriptor))
    }
}

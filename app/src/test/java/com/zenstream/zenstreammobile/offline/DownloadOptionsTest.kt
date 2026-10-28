package com.zenstream.zenstreammobile.offline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DownloadOptionsTest {
    @Test
    fun missingMemberReuseRequiresTheSameTrackAndLanguageChoices() {
        val previous =
            JSONObject()
                .put("profile", "original")
                .put("audioStreamId", 2)
                .put("language", "en")
                .put("subtitleStreamIndices", JSONArray().put(3))
        assertTrue(
            sameDownloadChoices(JSONObject(previous.toString()).put("skipExisting", true), previous)
        )
        assertFalse(
            sameDownloadChoices(JSONObject(previous.toString()).put("audioStreamId", 4), previous)
        )
        assertFalse(
            sameDownloadChoices(JSONObject(previous.toString()).put("language", "ja"), previous)
        )
    }

    @Test
    fun capabilityAndHdrFilteringNeverOffersAnUnsupportedConversion() {
        val caps =
            JSONObject().put("profiles", JSONArray(listOf("original", "audio-128k", "video-720p")))
        assertEquals(
            listOf("original", "audio-128k"),
            downloadProfiles(DownloadSpec("a", "Album", type = "MusicAlbum"), caps),
        )
        assertEquals(
            listOf("original"),
            downloadProfiles(DownloadSpec("v", "Movie"), caps, hdr = true),
        )
        caps.put("hdrToneMapping", true)
        assertEquals(
            listOf("original", "video-720p"),
            downloadProfiles(DownloadSpec("v", "Movie"), caps, hdr = true),
        )
    }

    @Test
    fun estimatesAreExplicitlyUnknownWithoutDurationOrOriginalSize() {
        assertNull(estimateDownloadBytes("original", JSONObject()))
        assertNull(estimateDownloadBytes("video-720p", null))
        assertEquals(
            960000L,
            estimateDownloadBytes("audio-128k", JSONObject().put("durationSeconds", 60)),
        )
    }

    @Test
    fun languageSelectionUsesEachExactSourceAndRejectsUnavailableChoices() {
        val source =
            JSONObject()
                .put("id", "source")
                .put(
                    "streams",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("index", 2)
                                .put("codec_type", "audio")
                                .put("tags", JSONObject().put("language", "jpn"))
                        )
                        .put(
                            JSONObject()
                                .put("index", 4)
                                .put("codec_type", "subtitle")
                                .put("tags", JSONObject().put("language", "eng"))
                        ),
                )
        val selected =
            languageSelections(
                source,
                JSONObject()
                    .put("audioLanguage", "ja")
                    .put("subtitleLanguages", JSONArray().put("en")),
            )
        assertEquals(2, selected.getInt("audioStreamId"))
        assertEquals(4, selected.getJSONArray("subtitleStreamIndices").getInt(0))
        assertEquals("source", selected.getString("sourceId"))
        assertThrows(IllegalStateException::class.java) {
            languageSelections(source, JSONObject().put("audioLanguage", "de"))
        }
    }
}

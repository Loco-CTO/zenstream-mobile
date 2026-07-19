package com.zenstream.zenstreammobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.zenstream.zenstreammobile.ui.player.InitialSeekController
import org.json.JSONArray
import org.json.JSONObject

class PlaybackParsingTest {
    @Test
    fun resolvesRelativeNegotiatedPlaybackUrlAgainstServer() {
        val session = com.zenstream.zenstreammobile.model.AuthSession(
            "https://jellyfin.example", "token", "user", "name"
        )
        val url = playbackUrl(
            session,
            "item-1",
            com.zenstream.zenstreammobile.model.MediaSource(
                id = "source-1",
                transcodingUrl = "/video/master.m3u8?MediaSourceId=source-1",
            )
        )
        assertTrue(url.startsWith("https://jellyfin.example/video/master.m3u8"))
        assertTrue(url.contains("api_key=token"))
    }

    @Test
    fun readsNegotiatedStreamTimelineOriginFromStartTimeTicks() {
        val session = com.zenstream.zenstreammobile.model.AuthSession(
            "https://jellyfin.example", "token", "user", "name"
        )
        val origin = playbackStreamStartPositionSeconds(
            session,
            com.zenstream.zenstreammobile.model.MediaSource(
                id = "source-1",
                transcodingUrl = "/video/master.m3u8?startTimeTicks=1250000000",
            ),
            requestedStartSeconds = 125.0,
        )

        assertEquals(125.0, origin, 0.001)
    }

    @Test
    fun fallbackPlaybackUsesRequestedStartAsItsTimelineOrigin() {
        val session = com.zenstream.zenstreammobile.model.AuthSession(
            "https://jellyfin.example", "token", "user", "name"
        )

        assertEquals(
            42.0,
            playbackStreamStartPositionSeconds(
                session,
                com.zenstream.zenstreammobile.model.MediaSource(id = "source-1"),
                requestedStartSeconds = 42.0,
            ),
            0.001,
        )
    }

    @Test
    fun reloadPositionIsRelativeToTheNewStreamOrigin() {
        assertEquals(15.0, playbackLocalPositionSeconds(140.0, 125.0), 0.001)
        assertEquals(0.0, playbackLocalPositionSeconds(100.0, 125.0), 0.001)
    }

    @Test
    fun subtitleRequestUsesRelativeTimestampsFromThePlaybackOrigin() {
        val query = subtitleWebVttQuery(
            com.zenstream.zenstreammobile.model.AuthSession(
                "https://jellyfin.example", "token", "user", "name"
            ),
            "item-1",
            "source-1",
            1_250_000_000L,
        )

        assertEquals("false", query["copyTimestamps"])
        assertEquals("false", query["addVttTimeMap"])
        assertEquals("1250000000", query["startPositionTicks"])
    }

    @Test
    fun parsesWebVttCuesAndStripsMarkup() {
        val cues = parseWebVttCues(
            "WEBVTT\n\n00:01.500 --> 00:03.000\n<00:01.500>Hello <b>world</b>!\n"
        )
        assertEquals(1, cues.size)
        assertEquals(1.5, cues.first().startSeconds, 0.001)
        assertEquals("Hello world!", cues.first().text)
    }

    @Test
    fun normalizesSubtitleStyleRangesAndFont() {
        val style = normalizeSubtitleStyle(
            com.zenstream.zenstreammobile.model.SubtitleStyle(
                fontFamily = "unknown",
                textScale = 400f,
                borderSize = -1f,
                backgroundOpacity = 150f,
            )
        )
        assertEquals("sans", style.fontFamily)
        assertEquals(200f, style.textScale)
        assertEquals(0f, style.borderSize)
        assertEquals(100f, style.backgroundOpacity)
    }

    @Test
    fun selectsCuesFromTheFreshPlaybackPositionAtBoundaries() {
        val cues = listOf(
            com.zenstream.zenstreammobile.model.SubtitleCue(1.0, 2.0, "first"),
            com.zenstream.zenstreammobile.model.SubtitleCue(2.0, 3.0, "second"),
        )

        assertEquals("first", activeSubtitleCues(cues, 1.999).single().text)
        assertEquals("second", activeSubtitleCues(cues, 2.0).single().text)
        assertTrue(activeSubtitleCues(cues, 0.5).isEmpty())
    }

    @Test
    fun initialSeekIsConsumedOnlyOnceWhenTheEngineBecomesReady() {
        val seek = InitialSeekController()
        seek.schedule(42.0)

        assertEquals(42.0, requireNotNull(seek.consume()), 0.001)
        assertEquals(null, seek.consume())
    }

    @Test
    fun zeroInitialSeekDoesNotScheduleASeek() {
        val seek = InitialSeekController()
        seek.schedule(0.0)

        assertEquals(null, seek.consume())
    }

    @Test
    fun manualSeekCancelsThePendingInitialSeek() {
        val seek = InitialSeekController()
        seek.schedule(42.0)
        seek.cancel()

        assertEquals(null, seek.consume())
    }

    @Test
    fun followsTheWebMarkerProviderOrder() {
        assertEquals(
            listOf(
                "/Episode/episode-1/IntroSkipperSegments",
                "/Episode/episode-1/Timestamps",
                "/MediaSegments/episode-1",
            ),
            playbackMarkerPaths("episode-1"),
        )
    }

    @Test
    fun parsesTypedMediaSegmentsFromItemsAndConvertsTicks() {
        val markers = parsePlaybackMarkers(
            JSONObject(
                """
                {"Items":[
                  {"Type":"Intro","StartTicks":100000000,"EndTicks":250000000},
                  {"Type":"Outro","StartTicks":800,"EndTicks":1200}
                ]}
                """.trimIndent()
            )
        )

        assertEquals(2, markers.size)
        assertEquals(10.0, markers[0].startSeconds, 0.001)
        assertEquals(25.0, markers[0].endSeconds, 0.001)
        assertEquals(800.0, markers[1].startSeconds, 0.001)
    }

    @Test
    fun parsesLegacyIntroSkipperObjectShape() {
        val markers = parsePlaybackMarkers(
            JSONObject(
                """
                {"IntroStart":100000000,"IntroEnd":200000000,
                 "CreditsStart":900000000,"CreditsEnd":950000000}
                """.trimIndent()
            )
        )

        assertEquals(2, markers.size)
        assertEquals(10.0, markers.first { it.type.name == "INTRO" }.startSeconds, 0.001)
        assertEquals(95.0, markers.first { it.type.name == "OUTRO" }.endSeconds, 0.001)
    }

    @Test
    fun derivesChapterMarkerEndFromTheNextChapterOrRuntime() {
        val item = com.zenstream.zenstreammobile.model.MediaItem(
            id = "episode-1",
            name = "Episode",
            runtimeTicks = 600_000_000L,
            chapters = parseChapters(
                JSONObject().put(
                    "Chapters",
                    JSONArray()
                        .put(JSONObject().put("StartPositionTicks", 0L).put("Name", "Opening"))
                        .put(JSONObject().put("StartPositionTicks", 120_000_000L).put("Name", "Story"))
                        .put(JSONObject().put("StartPositionTicks", 500_000_000L).put("Name", "Ending Credits")),
                )
            ),
        )

        val markers = chapterPlaybackSegments(item)

        assertEquals(2, markers.size)
        assertEquals(0.0, markers[0].startSeconds, 0.001)
        assertEquals(12.0, markers[0].endSeconds, 0.001)
        assertEquals(50.0, markers[1].startSeconds, 0.001)
        assertEquals(60.0, markers[1].endSeconds, 0.001)
    }

    @Test
    fun providerMarkerTypeWinsOverChapterFallback() {
        val provider = listOf(
            com.zenstream.zenstreammobile.model.PlaybackSegment(
                com.zenstream.zenstreammobile.model.PlaybackSegmentType.INTRO, 10.0, 20.0
            )
        )
        val chapters = listOf(
            com.zenstream.zenstreammobile.model.PlaybackSegment(
                com.zenstream.zenstreammobile.model.PlaybackSegmentType.INTRO, 1.0, 5.0
            ),
            com.zenstream.zenstreammobile.model.PlaybackSegment(
                com.zenstream.zenstreammobile.model.PlaybackSegmentType.OUTRO, 50.0, 60.0
            ),
        )

        val merged = mergePlaybackSegments(provider, chapters)

        assertEquals(2, merged.size)
        assertFalse(merged.any { it.startSeconds == 1.0 })
        assertTrue(merged.any { it.startSeconds == 50.0 })
    }

}

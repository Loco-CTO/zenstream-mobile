package com.zenstream.zenstreammobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

}

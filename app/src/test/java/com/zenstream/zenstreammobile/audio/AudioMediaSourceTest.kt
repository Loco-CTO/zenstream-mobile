package com.zenstream.zenstreammobile.audio

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMediaSourceTest {
    @Test
    fun m3u8UrlAlwaysSelectsHlsWithCanonicalMimeType() {
        val source =
            normalizeAudioSource(
                "https://server.example/session/master.m3u8?access=ticket",
                "application/vnd.apple.mpegurl",
                "remux",
            )

        assertEquals(AudioSourceKind.Hls, source.kind)
        assertEquals(MimeTypes.APPLICATION_M3U8, source.mimeType)
    }

    @Test
    fun audioTranscodeModeSelectsHlsEvenWithoutAnExtension() {
        val source =
            normalizeAudioSource(
                "https://server.example/session/stream",
                "application/vnd.apple.mpegurl",
                "audio-transcode",
            )

        assertEquals(AudioSourceKind.Hls, source.kind)
        assertEquals(MimeTypes.APPLICATION_M3U8, source.mimeType)
    }

    @Test
    fun directAudioKeepsItsProgressiveMimeType() {
        val source = normalizeAudioSource("https://server.example/audio.flac", null, "direct")

        assertEquals(AudioSourceKind.Progressive, source.kind)
        assertEquals(MimeTypes.AUDIO_FLAC, source.mimeType)
    }

    @Test
    fun unknownProgressiveSourcesDoNotInventAContainer() {
        val source = normalizeAudioSource("https://server.example/audio", null, "direct")

        assertEquals(AudioSourceKind.Progressive, source.kind)
        assertNull(source.mimeType)
        assertTrue(source.url.endsWith("/audio"))
    }
}

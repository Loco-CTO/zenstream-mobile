package com.zenstream.zenstreammobile.ui.player

import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackRecoveryTest {
    @Test
    fun audioRendererErrorsRequestAudioTranscoding() {
        val trackType =
            media3PlaybackErrorTrackType(
                errorType = ExoPlaybackException.TYPE_RENDERER,
                rendererIndex = 2,
                rendererTypeAt = { index ->
                    assertEquals(2, index)
                    C.TRACK_TYPE_AUDIO
                },
            )

        assertEquals(C.TRACK_TYPE_AUDIO, trackType)
        assertEquals("audio-transcode", playbackRecoveryMode(trackType))
    }

    @Test
    fun videoAndUnknownErrorsRequestVideoTranscoding() {
        val videoTrackType =
            media3PlaybackErrorTrackType(
                errorType = ExoPlaybackException.TYPE_RENDERER,
                rendererIndex = 0,
                rendererTypeAt = { C.TRACK_TYPE_VIDEO },
            )

        assertEquals(C.TRACK_TYPE_VIDEO, videoTrackType)
        assertEquals("video-transcode", playbackRecoveryMode(videoTrackType))
        assertEquals("video-transcode", playbackRecoveryMode(null))
    }

    @Test
    fun onlyAudioOrVideoRendererErrorsAreClassified() {
        assertNull(
            media3PlaybackErrorTrackType(
                errorType = ExoPlaybackException.TYPE_SOURCE,
                rendererIndex = 0,
                rendererTypeAt = { C.TRACK_TYPE_AUDIO },
            )
        )
        assertNull(
            media3PlaybackErrorTrackType(
                errorType = ExoPlaybackException.TYPE_RENDERER,
                rendererIndex = -1,
                rendererTypeAt = { C.TRACK_TYPE_AUDIO },
            )
        )
        assertNull(
            media3PlaybackErrorTrackType(
                errorType = ExoPlaybackException.TYPE_RENDERER,
                rendererIndex = 0,
                rendererTypeAt = { -1 },
            )
        )
    }
}

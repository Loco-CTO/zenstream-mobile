package com.zenstream.zenstreammobile.ui.screens

import com.zenstream.zenstreammobile.model.PlaybackTimeDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeDisplayTest {
    @Test
    fun formatsElapsedAndRemainingPlayerTimes() {
        assertEquals(
            "12:23 / 24:00",
            playbackTimeText(
                positionSeconds = 743.0,
                durationSeconds = 1_440.0,
                mode = PlaybackTimeDisplayMode.Elapsed,
            ),
        )
        assertEquals(
            "-11:37 / 24:00",
            playbackTimeText(
                positionSeconds = 743.0,
                durationSeconds = 1_440.0,
                mode = PlaybackTimeDisplayMode.Remaining,
            ),
        )
    }

    @Test
    fun clampsPositionToDurationAndSanitizesInvalidTimes() {
        assertEquals(
            "24:00 / 24:00",
            playbackTimeText(
                positionSeconds = 1_500.0,
                durationSeconds = 1_440.0,
                mode = PlaybackTimeDisplayMode.Elapsed,
            ),
        )
        assertEquals(
            "-0:00 / 24:00",
            playbackTimeText(
                positionSeconds = 1_500.0,
                durationSeconds = 1_440.0,
                mode = PlaybackTimeDisplayMode.Remaining,
            ),
        )
        assertEquals(
            "0:00 / 0:00",
            playbackTimeText(
                positionSeconds = Double.NaN,
                durationSeconds = Double.POSITIVE_INFINITY,
                mode = PlaybackTimeDisplayMode.Elapsed,
            ),
        )
    }

    @Test
    fun invalidStoredModesFallBackToRemaining() {
        assertEquals(
            PlaybackTimeDisplayMode.Remaining,
            PlaybackTimeDisplayMode.fromStorageValue(null),
        )
        assertEquals(
            PlaybackTimeDisplayMode.Remaining,
            PlaybackTimeDisplayMode.fromStorageValue("unknown"),
        )
        assertEquals(
            PlaybackTimeDisplayMode.Elapsed,
            PlaybackTimeDisplayMode.fromStorageValue("elapsed"),
        )
    }
}

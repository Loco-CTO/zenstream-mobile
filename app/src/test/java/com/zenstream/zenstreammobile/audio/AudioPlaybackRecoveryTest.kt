package com.zenstream.zenstreammobile.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlaybackRecoveryTest {
    @Test
    fun prefersTheMostRecentNonZeroPositionWhenMedia3ReportsZero() {
        assertEquals(
            55_000L,
            selectRecoveryPositionMs(
                playerPositionMs = 0L,
                lastKnownPositionMs = 55_000L,
                publishedPositionSeconds = 0L,
            ),
        )
    }

    @Test
    fun keepsTheLivePositionWhenItIsAvailable() {
        assertEquals(
            55_740L,
            selectRecoveryPositionMs(
                playerPositionMs = 55_740L,
                lastKnownPositionMs = 55_000L,
                publishedPositionSeconds = 54L,
            ),
        )
    }

    @Test
    fun clampsNegativePositionsAndAvoidsSecondOverflow() {
        assertEquals(
            Long.MAX_VALUE / 1_000L * 1_000L,
            selectRecoveryPositionMs(
                playerPositionMs = -1L,
                lastKnownPositionMs = -1L,
                publishedPositionSeconds = Long.MAX_VALUE,
            ),
        )
    }
}

package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackScreenTest {
    @Test
    fun timelineSitsAboveTransportControlsWithAVisibleGap() {
        assertEquals(16, PLAYBACK_TIMELINE_CONTROLS_GAP_DP)
    }

    @Test
    fun trickplaySpriteSizeCoversTheWholeSheetWithoutChangingTheFrameSize() {
        val (width, height) = trickplaySpriteSize(240.dp, 135.dp, columns = 10, rows = 10)

        assertEquals(2400.dp, width)
        assertEquals(1350.dp, height)
    }

    @Test
    fun nextUpIsShownOnlyForResolvedEpisodeNeighborsInTheFinalTenSeconds() {
        assertEquals(true, shouldShowNextUp(true, true, true, 90.0, 100.0))
        assertEquals(true, shouldShowNextUp(true, true, true, 99.0, 100.0))
        assertEquals(false, shouldShowNextUp(true, false, true, 99.0, 100.0))
        assertEquals(false, shouldShowNextUp(true, true, false, 99.0, 100.0))
        assertEquals(false, shouldShowNextUp(true, true, true, 89.9, 100.0))
        assertEquals(false, shouldShowNextUp(false, true, true, 99.0, 100.0))
    }
}

package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackScreenTest {
    @Test
    fun trickplaySpriteSizeCoversTheWholeSheetWithoutChangingTheFrameSize() {
        val (width, height) = trickplaySpriteSize(240.dp, 135.dp, columns = 10, rows = 10)

        assertEquals(2400.dp, width)
        assertEquals(1350.dp, height)
    }
}

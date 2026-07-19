package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.ui.components.progressPercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaHelpersTest {
    @Test
    fun progressPrefersServerPercentage() {
        assertEquals(42, progressPercent(MediaItem("1", "Movie", playedPercentage = 42.4)))
    }

    @Test
    fun progressUsesTicksWhenPercentageMissing() {
        assertEquals(
            50,
            progressPercent(MediaItem("1", "Movie", runtimeTicks = 100, playbackPositionTicks = 50))
        )
    }

    @Test
    fun progressIsAbsentWhenPlaybackHasNotStarted() {
        assertNull(progressPercent(MediaItem("1", "Movie", playedPercentage = 0.0)))
        assertNull(
            progressPercent(MediaItem("1", "Movie", runtimeTicks = 100, playbackPositionTicks = 0))
        )
    }
}

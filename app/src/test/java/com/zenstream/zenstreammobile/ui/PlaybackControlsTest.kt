package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.ui.screens.shouldAutoHidePlaybackControls
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControlsTest {
    @Test
    fun controlsCanAutoHideOnlyDuringActivePlayback() {
        assertTrue(shouldAutoHidePlaybackControls(visible = true, locked = false, menuOpen = false, isPlaying = true))
        assertFalse(shouldAutoHidePlaybackControls(visible = true, locked = false, menuOpen = false, isPlaying = false))
        assertFalse(shouldAutoHidePlaybackControls(visible = true, locked = true, menuOpen = false, isPlaying = true))
        assertFalse(shouldAutoHidePlaybackControls(visible = true, locked = false, menuOpen = true, isPlaying = true))
    }
}

package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.ui.screens.shouldAutoHidePlaybackControls
import com.zenstream.zenstreammobile.ui.screens.shouldShowAudioSelector
import com.zenstream.zenstreammobile.ui.screens.shouldShowSubtitleSelector
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

    @Test
    fun hidesTrackSelectorsWhenThereIsNoChoice() {
        assertFalse(shouldShowAudioSelector(0))
        assertFalse(shouldShowAudioSelector(1))
        assertTrue(shouldShowAudioSelector(2))
        assertFalse(shouldShowSubtitleSelector(0))
        assertTrue(shouldShowSubtitleSelector(1))
    }
}

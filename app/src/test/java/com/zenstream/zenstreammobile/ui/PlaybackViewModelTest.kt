package com.zenstream.zenstreammobile.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackViewModelTest {
    @Test
    fun clearsWatchedStateWhenPlaybackStartsForWatchedItem() {
        assertTrue(
            shouldClearPlayedOnPlaybackStart(
                isPlaying = true,
                played = true,
                resetAlreadyRequested = false,
            ),
        )
    }

    @Test
    fun doesNotClearUnwatchedOrAlreadyResetItems() {
        assertFalse(shouldClearPlayedOnPlaybackStart(true, false, false))
        assertFalse(shouldClearPlayedOnPlaybackStart(false, true, false))
        assertFalse(shouldClearPlayedOnPlaybackStart(true, true, true))
    }
}

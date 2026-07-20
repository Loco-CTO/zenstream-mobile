package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.model.MediaStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun defaultsToTheFirstSubtitleWhenTheServerDoesNotMarkOneDefault() {
        val subtitles = listOf(
            MediaStream(index = 4, type = "Subtitle"),
            MediaStream(index = 7, type = "Subtitle"),
        )

        assertEquals(4, selectSubtitleTrack(null, false, subtitles))
    }

    @Test
    fun preservesExplicitSubtitleOffAcrossPlaybackReloads() {
        assertNull(selectSubtitleTrack(null, true, listOf(MediaStream(4, "Subtitle"))))
    }
}

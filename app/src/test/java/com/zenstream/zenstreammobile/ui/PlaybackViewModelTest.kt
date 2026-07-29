package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.model.MediaSource
import com.zenstream.zenstreammobile.model.MediaItem
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

    @Test
    fun completionIsHandledOnlyOnceForTheActivePlaybackGeneration() {
        assertTrue(shouldHandlePlaybackCompletion(true, false, -1, 4))
        assertFalse(shouldHandlePlaybackCompletion(true, true, -1, 4))
        assertFalse(shouldHandlePlaybackCompletion(true, false, 4, 4))
        assertFalse(shouldHandlePlaybackCompletion(false, false, -1, 4))
        assertTrue(shouldHandlePlaybackCompletion(true, false, 4, 5))
    }

    @Test
    fun endedEpisodeWaitsForItsNextUpLookupBeforeClosingOrAdvancing() {
        assertTrue(shouldWaitForEpisodeNeighbors(false))
        assertFalse(shouldWaitForEpisodeNeighbors(true))
    }

    @Test
    fun nextUpFallbackSkipsTheEpisodeThatJustFinished() {
        val fallback = nextUpFallbackItem(
            listOf(
                MediaItem("episode-2", "Episode 2", type = "Episode"),
                MediaItem("episode-3", "Episode 3", type = "Episode"),
            ),
            "episode-2",
        )

        assertEquals("episode-3", fallback?.id)
    }

    @Test
    fun defaultsDetailSelectionAndKeepsSubtitleChoiceExplicit() {
        val selection = defaultTrackSelection(
            MediaSource(
                id = "source-1",
                mediaStreams = listOf(
                    MediaStream(1, "Audio"),
                    MediaStream(2, "Audio", isDefault = true),
                    MediaStream(4, "Subtitle"),
                    MediaStream(6, "Subtitle", isDefault = true),
                ),
            ),
        )

        assertEquals(2, selection.audioStreamId)
        assertEquals(6, selection.subtitleStreamIndex)
        assertTrue(selection.hasSubtitleSelection)
    }
}

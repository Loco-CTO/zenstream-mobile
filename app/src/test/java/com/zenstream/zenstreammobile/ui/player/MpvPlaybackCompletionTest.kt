package com.zenstream.zenstreammobile.ui.player

import com.zenstream.zenstreammobile.ui.EpisodeCompletionAction
import com.zenstream.zenstreammobile.ui.episodeCompletionAction
import com.zenstream.zenstreammobile.ui.shouldHandlePlaybackCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvPlaybackCompletionTest {
    @Test
    fun keepOpenEofClosesPlayerWithoutAnEndFileEventOrNextEpisode() {
        val ended =
            mpvPlaybackEnded(
                EngineState(ready = true, isPlaying = false),
                sourceReady = true,
                eofReached = true,
            )

        assertTrue(ended)
        assertTrue(shouldHandlePlaybackCompletion(ended, false, -1, 1))
        assertEquals(
            EpisodeCompletionAction.CLOSE,
            episodeCompletionAction(episodeNeighborsLoaded = true, nextEpisode = null),
        )
    }

    @Test
    fun pausedOrBufferingPlaybackIsNotCompletion() {
        assertFalse(mpvPlaybackEnded(EngineState(isPlaying = false), true, false))
        assertFalse(mpvPlaybackEnded(EngineState(isBuffering = true), true, false))
        assertFalse(mpvPlaybackEnded(EngineState(), true, null))
    }

    @Test
    fun loadingAReplacementIgnoresTheOutgoingFilesEof() {
        assertFalse(mpvPlaybackEnded(EngineState(), sourceReady = false, eofReached = true))
        assertFalse(mpvPlaybackEnded(EngineState(), sourceReady = true, eofReached = false))
        assertTrue(mpvPlaybackEnded(EngineState(), sourceReady = true, eofReached = true))
    }

    @Test
    fun completionRemainsLatchedUntilTheNextPrepareResetsEngineState() {
        val completed = EngineState(ended = true)

        assertTrue(mpvPlaybackEnded(completed, true, false))
        assertTrue(mpvPlaybackEnded(completed, true, null))
        assertFalse(mpvPlaybackEnded(EngineState(), false, false))
        assertFalse(shouldHandlePlaybackCompletion(true, false, 1, 1))
    }
}

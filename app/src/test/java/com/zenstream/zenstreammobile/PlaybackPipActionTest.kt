package com.zenstream.zenstreammobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackPipActionTest {
    @Test
    fun knownPipActionsMapOnlyToVideoActions() {
        assertEquals(
            PlaybackPipAction.Toggle,
            playbackPipActionForIntent(PlaybackPipActionReceiver.ACTION_TOGGLE),
        )
        assertEquals(
            PlaybackPipAction.Previous,
            playbackPipActionForIntent(PlaybackPipActionReceiver.ACTION_PREVIOUS),
        )
        assertEquals(
            PlaybackPipAction.Next,
            playbackPipActionForIntent(PlaybackPipActionReceiver.ACTION_NEXT),
        )
        assertEquals(
            PlaybackPipAction.Close,
            playbackPipActionForIntent(PlaybackPipActionReceiver.ACTION_CLOSE),
        )
    }

    @Test
    fun unknownPipActionIsIgnored() {
        assertNull(playbackPipActionForIntent("com.zenstream.unknown"))
    }
}

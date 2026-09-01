package com.zenstream.zenstreammobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackActivityContractTest {
    @Test
    fun parsesRequiredItemIdAndOptionalName() {
        assertEquals(
            PlaybackLaunchArgs("item-1", "Example"),
            parsePlaybackLaunchArgs("item-1", "Example"),
        )
        assertEquals(
            PlaybackLaunchArgs("item-1", ""),
            parsePlaybackLaunchArgs("item-1", null),
        )
    }

    @Test
    fun rejectsMissingItemId() {
        assertNull(parsePlaybackLaunchArgs(null, "Example"))
        assertNull(parsePlaybackLaunchArgs("  ", "Example"))
    }

    @Test
    fun preservesTrackSelectionAndExplicitSubtitleOff() {
        assertEquals(
            PlaybackLaunchArgs(
                "item-1",
                "Example",
                audioStreamId = 3,
                subtitleStreamIndex = null,
                hasSubtitleSelection = true,
            ),
            parsePlaybackLaunchArgs(
                "item-1",
                "Example",
                audioStreamId = 3,
                hasSubtitleSelection = true,
            ),
        )
    }

    @Test
    fun launchGateRejectsOverlappingPlaybackActivities() {
        val gate = PlaybackActivityLaunchGate()

        assertTrue(gate.beginLaunch())
        assertTrue(gate.claimActivity())
        assertFalse(gate.beginLaunch())
        assertFalse(gate.claimActivity())

        gate.releaseActivity()

        assertTrue(gate.beginLaunch())
        gate.cancelLaunch()
        assertTrue(gate.claimActivity())
    }

    @Test
    fun backgroundPauseExcludesFinishingPiPAndConfigurationChanges() {
        assertTrue(shouldPausePlaybackForBackground(false, false, false, false))
        assertFalse(shouldPausePlaybackForBackground(true, false, false, false))
        assertFalse(shouldPausePlaybackForBackground(false, true, false, false))
        assertFalse(shouldPausePlaybackForBackground(false, false, true, false))
        assertFalse(shouldPausePlaybackForBackground(false, false, false, true))
    }
}

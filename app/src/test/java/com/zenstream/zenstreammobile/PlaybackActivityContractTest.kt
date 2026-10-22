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
    fun backgroundPauseStopsAfterPipDismissalButPreservesTransitions() {
        assertTrue(shouldPausePlaybackForBackground(false, false, false))
        assertTrue(shouldPausePlaybackForBackground(false, true, false))
        assertFalse(shouldPausePlaybackForBackground(true, false, false))
        assertFalse(shouldPausePlaybackForBackground(false, false, true))
    }

    @Test
    fun finishesStoppedPlaybackActivityAfterPipDismissalOnly() {
        assertTrue(
            shouldFinishPlaybackAfterPictureInPictureDismissal(
                hasEnteredPictureInPicture = true,
                activityStopped = true,
                isInPictureInPictureMode = false,
                isChangingConfigurations = false,
            )
        )
        assertFalse(
            shouldFinishPlaybackAfterPictureInPictureDismissal(
                hasEnteredPictureInPicture = true,
                activityStopped = false,
                isInPictureInPictureMode = false,
                isChangingConfigurations = false,
            )
        )
        assertFalse(
            shouldFinishPlaybackAfterPictureInPictureDismissal(
                hasEnteredPictureInPicture = false,
                activityStopped = true,
                isInPictureInPictureMode = false,
                isChangingConfigurations = false,
            )
        )
        assertFalse(
            shouldFinishPlaybackAfterPictureInPictureDismissal(
                hasEnteredPictureInPicture = true,
                activityStopped = true,
                isInPictureInPictureMode = true,
                isChangingConfigurations = false,
            )
        )
        assertFalse(
            shouldFinishPlaybackAfterPictureInPictureDismissal(
                hasEnteredPictureInPicture = true,
                activityStopped = true,
                isInPictureInPictureMode = false,
                isChangingConfigurations = true,
            )
        )
    }

    @Test
    fun automaticPictureInPictureRequiresEnabledSettingAndActivePlayback() {
        assertTrue(shouldAutomaticallyEnterPictureInPicture(true, true))
        assertFalse(shouldAutomaticallyEnterPictureInPicture(false, true))
        assertFalse(shouldAutomaticallyEnterPictureInPicture(true, false))
        assertFalse(shouldAutomaticallyEnterPictureInPicture(false, false))
    }
}

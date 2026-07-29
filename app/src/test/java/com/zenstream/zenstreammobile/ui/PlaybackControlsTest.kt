package com.zenstream.zenstreammobile.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.zenstream.zenstreammobile.ui.screens.GestureStartExclusion
import com.zenstream.zenstreammobile.ui.screens.shouldAutoHidePlaybackControls
import com.zenstream.zenstreammobile.ui.screens.clampSeekTarget
import com.zenstream.zenstreammobile.ui.screens.dragSeekDeltaSeconds
import com.zenstream.zenstreammobile.ui.screens.feedbackSeconds
import com.zenstream.zenstreammobile.ui.screens.isGestureStartProtected
import com.zenstream.zenstreammobile.ui.screens.isHorizontalSeekGesture
import com.zenstream.zenstreammobile.ui.screens.quickSeekDeltaForTap
import com.zenstream.zenstreammobile.ui.screens.shouldShowAudioSelector
import com.zenstream.zenstreammobile.ui.screens.shouldShowSubtitleSelector
import com.zenstream.zenstreammobile.model.PlaybackSegment
import com.zenstream.zenstreammobile.model.PlaybackSegmentType
import com.zenstream.zenstreammobile.ui.screens.timelinePositionAt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControlsTest {
    @Test
    fun timelineScrubClampsToTheMediaRange() {
        assertEquals(0.0, timelinePositionAt(-10f, 100f, 200.0), 0.001)
        assertEquals(100.0, timelinePositionAt(50f, 100f, 200.0), 0.001)
        assertEquals(200.0, timelinePositionAt(110f, 100f, 200.0), 0.001)
    }

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

    @Test
    fun exposesOnlyTheSegmentContainingTheCurrentPosition() {
        val intro = PlaybackSegment(PlaybackSegmentType.INTRO, 10.0, 20.0)
        val state = PlaybackUiState(segments = listOf(intro))

        assertEquals(intro, state.activeSegmentAt(10.0))
        assertEquals(intro, state.activeSegmentAt(19.99))
        assertNull(state.activeSegmentAt(20.0))
    }

    @Test
    fun formatsPlaybackSpeedWithoutUnnecessaryTrailingZeros() {
        assertEquals("0.5", com.zenstream.zenstreammobile.ui.screens.formatPlaybackSpeedValue(.5f))
        assertEquals("1", com.zenstream.zenstreammobile.ui.screens.formatPlaybackSpeedValue(1f))
        assertEquals("1.25", com.zenstream.zenstreammobile.ui.screens.formatPlaybackSpeedValue(1.25f))
    }

    @Test
    fun quickSeekUsesTheSideOfThePlayerForFiveSecondJumps() {
        assertEquals(-5.0, quickSeekDeltaForTap(100f, 1_000), 0.001)
        assertEquals(5.0, quickSeekDeltaForTap(900f, 1_000), 0.001)
        assertEquals(5.0, quickSeekDeltaForTap(500f, 1_000), 0.001)
    }

    @Test
    fun dragSeekUsesHalfThePlayerWidthForTheFullDuration() {
        assertEquals(30.0, dragSeekDeltaSeconds(250f, 1_000, 240.0), 0.001)
        assertEquals(-30.0, dragSeekDeltaSeconds(-250f, 1_000, 240.0), 0.001)
    }

    @Test
    fun gestureStartExclusionProtectsEverySystemEdge() {
        val exclusion = GestureStartExclusion(left = 32f, top = 48f, right = 40f, bottom = 56f)
        val playerSize = IntSize(width = 1_000, height = 2_000)

        assertTrue(isGestureStartProtected(Offset(31f, 1_000f), playerSize, exclusion))
        assertTrue(isGestureStartProtected(Offset(500f, 47f), playerSize, exclusion))
        assertTrue(isGestureStartProtected(Offset(961f, 1_000f), playerSize, exclusion))
        assertTrue(isGestureStartProtected(Offset(500f, 1_945f), playerSize, exclusion))
        assertFalse(isGestureStartProtected(Offset(500f, 1_000f), playerSize, exclusion))
    }

    @Test
    fun seekGestureMustBeDecisivelyHorizontal() {
        assertTrue(isHorizontalSeekGesture(Offset(30f, 10f)))
        assertTrue(isHorizontalSeekGesture(Offset(-30f, 10f)))
        assertFalse(isHorizontalSeekGesture(Offset(10f, 30f)))
        assertFalse(isHorizontalSeekGesture(Offset(15f, 10f)))
    }

    @Test
    fun seekTargetIsClampedToMediaBounds() {
        assertEquals(0.0, clampSeekTarget(-1.0, 100.0), 0.001)
        assertEquals(100.0, clampSeekTarget(101.0, 100.0), 0.001)
        assertEquals(42.0, clampSeekTarget(42.0, 100.0), 0.001)
    }

    @Test
    fun feedbackAlwaysShowsAtLeastOneSecond() {
        assertEquals(1, feedbackSeconds(0.2))
        assertEquals(12, feedbackSeconds(-12.8))
    }
}

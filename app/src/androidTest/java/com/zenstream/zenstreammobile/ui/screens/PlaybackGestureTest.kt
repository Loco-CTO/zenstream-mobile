package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.platform.testTag
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlaybackGestureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rightDoubleTapSeeksForwardFiveSeconds() {
        val seekBy = mutableStateListOf<Double>()

        composeRule.setContent {
            ZenStreamTheme {
                PlaybackGestureLayer(
                    modifier = Modifier.fillMaxSize().testTag("gesture-layer"),
                    controlsLocked = false,
                    positionSeconds = 50.0,
                    durationSeconds = 100.0,
                    onToggleControls = {},
                    onSeekBy = seekBy::add,
                    onSeekTo = {},
                    onSeekFeedback = {},
                )
            }
        }

        composeRule.onNodeWithTag("gesture-layer").performTouchInput {
            doubleClick(centerRight)
        }

        composeRule.runOnIdle {
            assertEquals(listOf(5.0), seekBy.toList())
        }
    }

    @Test
    fun leftDoubleTapSeeksBackwardFiveSeconds() {
        val seekBy = mutableStateListOf<Double>()

        composeRule.setContent {
            ZenStreamTheme {
                PlaybackGestureLayer(
                    modifier = Modifier.fillMaxSize().testTag("gesture-layer"),
                    controlsLocked = false,
                    positionSeconds = 50.0,
                    durationSeconds = 100.0,
                    onToggleControls = {},
                    onSeekBy = seekBy::add,
                    onSeekTo = {},
                    onSeekFeedback = {},
                )
            }
        }

        composeRule.onNodeWithTag("gesture-layer").performTouchInput {
            doubleClick(centerLeft)
        }

        composeRule.runOnIdle {
            assertEquals(listOf(-5.0), seekBy.toList())
        }
    }

    @Test
    fun horizontalDragSeeksInTheDragDirection() {
        val seekTo = mutableStateListOf<Double>()
        val feedback = mutableStateListOf<SeekFeedback>()

        composeRule.setContent {
            ZenStreamTheme {
                PlaybackGestureLayer(
                    modifier = Modifier.fillMaxSize().testTag("gesture-layer"),
                    controlsLocked = false,
                    positionSeconds = 50.0,
                    durationSeconds = 100.0,
                    onToggleControls = {},
                    onSeekBy = {},
                    onSeekTo = seekTo::add,
                    onSeekFeedback = feedback::add,
                )
            }
        }

        composeRule.onNodeWithTag("gesture-layer").performTouchInput { swipeRight() }

        composeRule.runOnIdle {
            assertTrue(seekTo.last() > 50.0)
            assertEquals(SeekDirection.FORWARD, feedback.last().direction)
        }
    }

    @Test
    fun lockedControlsIgnoreSingleTapButKeepQuickSeek() {
        var toggleCount by mutableStateOf(0)
        val seekBy = mutableStateListOf<Double>()

        composeRule.setContent {
            ZenStreamTheme {
                PlaybackGestureLayer(
                    modifier = Modifier.fillMaxSize().testTag("gesture-layer"),
                    controlsLocked = true,
                    positionSeconds = 50.0,
                    durationSeconds = 100.0,
                    onToggleControls = { toggleCount++ },
                    onSeekBy = seekBy::add,
                    onSeekTo = {},
                    onSeekFeedback = {},
                )
            }
        }

        composeRule.onNodeWithTag("gesture-layer").performTouchInput {
            click(center)
            doubleClick(centerRight)
        }

        composeRule.runOnIdle {
            assertEquals(0, toggleCount)
            assertEquals(listOf(5.0), seekBy.toList())
        }
    }

    @Test
    fun seekFeedbackShowsLocalizedDirectionAndAmount() {
        composeRule.setContent {
            ZenStreamTheme {
                SeekFeedbackOverlay(SeekFeedback(SeekDirection.FORWARD, 5))
            }
        }

        composeRule.onNodeWithText("5 seconds forward").assertIsDisplayed()
    }
}

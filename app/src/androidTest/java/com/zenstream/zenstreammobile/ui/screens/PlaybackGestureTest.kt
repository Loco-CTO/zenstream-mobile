package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.platform.testTag
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
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
                    onSeekFeedback = feedback::add,
                    onSurfaceDragEnd = seekTo::add,
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
    fun surfaceDragPreviewsWithoutSeekingUntilRelease() {
        val previewTargets = mutableStateListOf<Double>()
        val seekTo = mutableStateListOf<Double>()

        composeRule.setContent {
            ZenStreamTheme {
                PlaybackGestureLayer(
                    modifier = Modifier.fillMaxSize().testTag("gesture-layer"),
                    controlsLocked = false,
                    positionSeconds = 50.0,
                    durationSeconds = 100.0,
                    onToggleControls = {},
                    onSeekBy = {},
                    onSeekTo = {},
                    onSeekFeedback = {},
                    onSurfaceDragStart = {},
                    onSurfaceDragChanged = {
                        assertTrue(seekTo.isEmpty())
                        previewTargets += it
                    },
                    onSurfaceDragEnd = seekTo::add,
                )
            }
        }

        composeRule.onNodeWithTag("gesture-layer").performTouchInput { swipeRight() }

        composeRule.runOnIdle {
            assertTrue(previewTargets.isNotEmpty())
            assertEquals(listOf(previewTargets.last()), seekTo.toList())
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
                Box(Modifier.fillMaxSize()) {
                    SeekFeedbackOverlay(
                        feedback = SeekFeedback(SeekDirection.FORWARD, 5),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }

        val expectedLabel = InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(R.string.player_quick_seek_forward, 5)
        composeRule.onNodeWithText(expectedLabel).assertIsDisplayed()
    }

    @Test
    fun surfaceTrickplayOverlayShowsTargetDurationAndAccessibleDescription() {
        composeRule.setContent {
            ZenStreamTheme {
                SurfaceTrickplayOverlay(
                    session = com.zenstream.zenstreammobile.model.AuthSession(
                        "https://example.com",
                        "token",
                        "user",
                        "Test",
                    ),
                    positionSeconds = 65.0,
                    durationSeconds = 125.0,
                    preview = null,
                    onPreviewError = {},
                )
            }
        }

        composeRule.onNodeWithTag("surface-trickplay-preview").assertIsDisplayed()
        composeRule.onNodeWithText("1:05 / 2:05").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription("Seek preview: 1:05 of 2:05")
            .assertIsDisplayed()
    }
}

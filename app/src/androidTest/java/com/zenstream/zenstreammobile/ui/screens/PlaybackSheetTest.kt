package com.zenstream.zenstreammobile.ui.screens

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.ui.player.VideoDecoderPreference
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSheetTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun subtitleListSettlesAfterFastDownwardSwipe() {
        var selectedSubtitle: Int? = null
        composeRule.runOnUiThread {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.activity.resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
        }
        composeRule.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(composeRule.activity.window, false)
            WindowCompat.getInsetsController(
                    composeRule.activity.window,
                    composeRule.activity.window.decorView,
                )
                .hide(WindowInsetsCompat.Type.systemBars())
        }
        composeRule.setContent {
            ZenStreamTheme {
                PlayerBottomSheet(
                    sheet = PlayerSheet.Subtitles,
                    selectedSubtitle = null,
                    selectedAudio = null,
                    selectedQuality = 0,
                    audio = emptyList(),
                    subtitles =
                        (1..2).map { MediaStream(it, "Subtitle", displayTitle = "Track $it") },
                    qualities = emptyList(),
                    speed = 1f,
                    onDismiss = {},
                    onSubtitle = { selectedSubtitle = it },
                    onAudio = {},
                    onQuality = {},
                    onSpeed = {},
                )
            }
        }
        composeRule.onNodeWithText("Track 1").performTouchInput {
            swipe(start = center, end = center - Offset(0f, 120f), durationMillis = 50)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Track 1").performTouchInput {
            swipe(start = center, end = center + Offset(0f, 120f), durationMillis = 50)
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Track 1").performClick()
        composeRule.runOnIdle { assertEquals(1, selectedSubtitle) }
    }

    @Test
    fun subtitleSheetCanStillBeDismissedBySwipe() {
        composeRule.setContent {
            var sheet by remember { mutableStateOf<PlayerSheet?>(PlayerSheet.Subtitles) }
            ZenStreamTheme {
                PlayerBottomSheet(
                    sheet = sheet,
                    selectedSubtitle = null,
                    selectedAudio = null,
                    selectedQuality = 0,
                    audio = emptyList(),
                    subtitles = listOf(MediaStream(1, "Subtitle", language = "English")),
                    qualities = emptyList(),
                    speed = 1f,
                    onDismiss = { sheet = null },
                    onSubtitle = {},
                    onAudio = {},
                    onQuality = {},
                    onSpeed = {},
                )
            }
        }
        composeRule.onNodeWithText("Subtitle track").performTouchInput {
            swipe(start = center, end = center + Offset(0f, 600f), durationMillis = 100)
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Subtitle track").assertCountEquals(0)
    }

    @Test
    fun speedSheetShowsTitleAndSelectedValue() {
        composeRule.setContent {
            ZenStreamTheme {
                PlayerBottomSheet(
                    sheet = PlayerSheet.Speed,
                    selectedSubtitle = null,
                    selectedAudio = null,
                    selectedQuality = 0,
                    audio = emptyList(),
                    subtitles = emptyList(),
                    qualities = emptyList(),
                    speed = 1f,
                    onDismiss = {},
                    onSubtitle = {},
                    onAudio = {},
                    onQuality = {},
                    onSpeed = {},
                )
            }
        }

        composeRule.onNodeWithText("Playback speed").assertIsDisplayed()
        composeRule.onNodeWithText("1×").assertIsSelected()
    }

    @Test
    fun selectingQualityCallsBackImmediately() {
        var selectedQuality = -1
        composeRule.setContent {
            ZenStreamTheme {
                PlayerBottomSheet(
                    sheet = PlayerSheet.Quality,
                    selectedSubtitle = null,
                    selectedAudio = null,
                    selectedQuality = 0,
                    audio = emptyList(),
                    subtitles = emptyList(),
                    qualities = listOf(0, 8_000_000),
                    speed = 1f,
                    onDismiss = {},
                    onSubtitle = {},
                    onAudio = {},
                    onQuality = { selectedQuality = it },
                    onSpeed = {},
                )
            }
        }

        composeRule.onNodeWithText("8 Mbps").performClick()
        composeRule.runOnIdle { assertEquals(8_000_000, selectedQuality) }
    }

    @Test
    fun subtitleOffIsSelectedAndSelectionCanDismissSheet() {
        var selectedSubtitle: Int? = null
        composeRule.setContent {
            var sheet by remember { mutableStateOf<PlayerSheet?>(PlayerSheet.Subtitles) }
            ZenStreamTheme {
                PlayerBottomSheet(
                    sheet = sheet,
                    selectedSubtitle = selectedSubtitle,
                    selectedAudio = null,
                    selectedQuality = 0,
                    audio = emptyList(),
                    subtitles = listOf(MediaStream(2, "Subtitle", language = "Japanese")),
                    qualities = emptyList(),
                    speed = 1f,
                    onDismiss = { sheet = null },
                    onSubtitle = {
                        selectedSubtitle = it
                        sheet = null
                    },
                    onAudio = {},
                    onQuality = {},
                    onSpeed = {},
                )
            }
        }

        composeRule.onNodeWithText("Subtitles off").assertIsSelected().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Subtitles off").assertCountEquals(0)
    }

    @Test
    fun media3QualitySheetExposesPerPlaybackDecoderSelection() {
        var selected = VideoDecoderPreference.AUTO
        composeRule.setContent {
            ZenStreamTheme {
                PlayerBottomSheet(
                    sheet = PlayerSheet.Quality,
                    selectedSubtitle = null,
                    selectedAudio = null,
                    selectedQuality = 0,
                    audio = emptyList(),
                    subtitles = emptyList(),
                    qualities = listOf(0, 8_000_000),
                    speed = 1f,
                    onDismiss = {},
                    onSubtitle = {},
                    onAudio = {},
                    onQuality = {},
                    onSpeed = {},
                    decoderPreference = VideoDecoderPreference.AUTO,
                    onVideoDecoder = { selected = it },
                )
            }
        }
        composeRule.onNodeWithText("Video decoder").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("App software (FFmpeg)").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(VideoDecoderPreference.APP_SOFTWARE, selected) }
    }
}

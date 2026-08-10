package com.zenstream.zenstreammobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSheetTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

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
}

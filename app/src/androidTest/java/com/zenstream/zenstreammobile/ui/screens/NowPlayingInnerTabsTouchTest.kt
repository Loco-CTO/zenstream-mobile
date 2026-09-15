package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.ui.components.ToastHost
import com.zenstream.zenstreammobile.ui.components.rememberToastHostState
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NowPlayingInnerTabsTouchTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun emptyToastHostDoesNotInterceptFullMusicTabTargets() {
        var queueClicks by mutableIntStateOf(0)
        var lyricsClicks by mutableIntStateOf(0)

        composeRule.setContent {
            ZenStreamTheme {
                Box(Modifier.fillMaxSize()) {
                    NowPlayingInnerTabs(
                        selectedPage = 0,
                        accent = Color.Magenta,
                        onQueueClick = { queueClicks++ },
                        onLyricsClick = { lyricsClicks++ },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    ToastHost(state = rememberToastHostState())
                }
            }
        }

        tapTopAndBottom("audio-tab-queue")
        tapTopAndBottom("audio-tab-lyrics")

        composeRule.runOnIdle {
            assertEquals(2, queueClicks)
            assertEquals(2, lyricsClicks)
        }
    }

    @Test
    fun dismissingLastToastReleasesMusicTabTargets() {
        var queueClicks by mutableIntStateOf(0)

        composeRule.setContent {
            ZenStreamTheme {
                val toast = rememberToastHostState()
                LaunchedEffect(Unit) { toast.success("Temporary notice") }
                Box(Modifier.fillMaxSize()) {
                    NowPlayingInnerTabs(
                        selectedPage = 0,
                        accent = Color.Magenta,
                        onQueueClick = { queueClicks++ },
                        onLyricsClick = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                    ToastHost(state = toast)
                }
            }
        }

        composeRule.onNodeWithText("Temporary notice").assertExists()
        val dismissDescription =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .getString(R.string.toast_dismiss)
        composeRule.onNodeWithContentDescription(dismissDescription).performClick()
        composeRule.waitUntil(timeoutMillis = 1_000) {
            composeRule
                .onAllNodesWithText("Temporary notice")
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .isEmpty()
        }

        tapTopAndBottom("audio-tab-queue")

        composeRule.runOnIdle { assertEquals(2, queueClicks) }
    }

    private fun tapTopAndBottom(testTag: String) {
        composeRule.onNodeWithTag(testTag).performTouchInput {
            click(Offset(center.x, 2f))
            click(Offset(center.x, center.y * 2f - 2f))
        }
    }
}

package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.AudioLyrics
import com.zenstream.zenstreammobile.model.LyricLine
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LyricsPanelTest {
    @get:Rule val compose = createComposeRule()

    private val position = mutableLongStateOf(0)
    private val wrappedLyric = "A longer lyric that wraps across several lines in this viewport"
    private val lines =
        List(30) { index ->
            LyricLine(
                text = if (index == 1) wrappedLyric else "Line ${index + 1}",
                startSeconds = index + 1.0,
            )
        }

    @Test
    fun followingKeepsWrappedLineHeightAndRealignsAfterSeeks() {
        showLyrics()
        val firstLineTop = lineTop("Line 1")
        val wrappedHeight = lineHeight(wrappedLyric)

        seekTo(2)
        awaitLineAtTop(wrappedLyric, firstLineTop.value)
        assertEquals(wrappedHeight, lineHeight(wrappedLyric))

        seekTo(19)
        awaitLineAtTop("Line 19", firstLineTop.value)
        seekTo(0)
        awaitLineAtTop("Line 1", firstLineTop.value)
        seekTo(30)
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodes(hasText("Line 30")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Line 30").assertIsDisplayed()
    }

    @Test
    fun swipePausesFollowingUntilRequested() {
        showLyrics()
        val firstLineTop = lineTop("Line 1")
        val followLabel =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .getString(R.string.audio_follow_current_line)
        val followButton = compose.onNodeWithText(followLabel)
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp(durationMillis = 500L) }
        followButton.assertIsDisplayed()

        seekTo(19)
        followButton.assertIsDisplayed()
        compose.onNodeWithText("Line 19").assertDoesNotExist()

        followButton.performClick()
        awaitLineAtTop("Line 19", firstLineTop.value)
        followButton.assertDoesNotExist()
    }

    private fun showLyrics() {
        val lyrics = AudioLyrics(source = "test", timed = true, lines = lines)
        compose.setContent {
            ZenStreamTheme {
                Box {
                    LyricsPanel(
                        modifier = Modifier.size(width = 220.dp, height = 250.dp),
                        trackKey = "test-track",
                        lyrics = lyrics,
                        loading = false,
                        error = false,
                        positionSeconds = position.longValue,
                        positionMillis = position.longValue * 1_000,
                        positionUpdatedAtElapsedRealtime = 1L,
                        isPlaying = false,
                        accent = Color.Magenta,
                        onSeek = {},
                        onRetry = {},
                    )
                }
            }
        }
    }

    private fun seekTo(seconds: Long) {
        compose.runOnIdle { position.longValue = seconds }
    }

    private fun lineTop(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top

    private fun lineHeight(text: String) =
        compose.onNodeWithText(text).getUnclippedBoundsInRoot().let { it.bottom - it.top }

    private fun awaitLineAtTop(text: String, top: Float) {
        compose.waitUntil(timeoutMillis = 3_000) {
            compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() &&
                abs(lineTop(text).value - top) < 1f
        }
    }
}

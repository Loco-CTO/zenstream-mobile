package com.zenstream.zenstreammobile.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
    fun tappingLyricSeeksToFractionalSecond() {
        var seekMillis = -1L
        val lyrics =
            AudioLyrics(
                source = "test",
                timed = true,
                lines = listOf(LyricLine(text = "Fractional line", startSeconds = 12.75)),
            )
        compose.setContent {
            ZenStreamTheme {
                LyricsPanel(
                    trackKey = "fractional",
                    lyrics = lyrics,
                    loading = false,
                    error = false,
                    positionSeconds = 0,
                    positionMillis = 0,
                    positionUpdatedAtElapsedRealtime = 1,
                    isPlaying = false,
                    accent = Color.Magenta,
                    onSeek = { seekMillis = it },
                    onRetry = {},
                )
            }
        }
        compose.onNodeWithText("Fractional line").performClick()
        compose.runOnIdle { assertEquals(12_750L, seekMillis) }
    }

    @Test
    fun pausingFreezesTheCurrentLyricAndResumingFollowsPlayback() {
        val positionSeconds = mutableLongStateOf(2L)
        val isPlaying = mutableStateOf(false)
        val lyrics =
            AudioLyrics(
                source = "test",
                timed = true,
                lines =
                    // Leave enough content below line 2 for it to scroll to the content inset.
                    List(8) { index ->
                        LyricLine(text = "Playback line ${index + 1}", startSeconds = index * 2.0)
                    },
            )
        compose.setContent {
            ZenStreamTheme {
                Box {
                    LyricsPanel(
                        modifier =
                            Modifier.size(width = 220.dp, height = 250.dp).testTag("lyrics-panel"),
                        trackKey = "pause-resume-track",
                        lyrics = lyrics,
                        loading = false,
                        error = false,
                        positionSeconds = positionSeconds.longValue,
                        positionMillis = positionSeconds.longValue * 1_000L,
                        positionUpdatedAtElapsedRealtime = 0L,
                        isPlaying = isPlaying.value,
                        accent = Color.Magenta,
                        onSeek = {},
                        onRetry = {},
                    )
                }
            }
        }

        val panelTop = compose.onNodeWithTag("lyrics-panel").getUnclippedBoundsInRoot().top
        val expectedLineTop = panelTop + 12.dp
        awaitLineAtTop("Playback line 2", expectedLineTop.value)

        compose.runOnIdle { isPlaying.value = true }
        advanceComposeTime(300L)
        assertEquals(expectedLineTop.value, lineTop("Playback line 2").value, 1f)

        compose.runOnIdle { isPlaying.value = false }
        advanceComposeTime(700L)
        assertEquals(expectedLineTop.value, lineTop("Playback line 2").value, 1f)

        compose.runOnIdle { isPlaying.value = true }
        awaitLineAtTop("Playback line 3", expectedLineTop.value)
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
        awaitLineDisplayed("Line 30")
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
        compose.mainClock.autoAdvance = false
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            advanceComposeTime(16L)
            if (
                compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() &&
                    abs(lineTop(text).value - top) < 1f
            ) {
                return
            }
        }
        val actualTop = runCatching { lineTop(text).value }.getOrNull()
        val offsetFromExpected = actualTop?.minus(top)
        throw AssertionError(
            "$text top=$top, actual=$actualTop (delta=$offsetFromExpected) after 3000 ms"
        )
    }

    private fun awaitLineDisplayed(text: String) {
        compose.mainClock.autoAdvance = false
        val deadline = SystemClock.elapsedRealtime() + 3_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            advanceComposeTime(16L)
            if (compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()) return
        }
        throw AssertionError("$text did not become displayed within 3000 ms")
    }

    private fun advanceComposeTime(durationMillis: Long) {
        var advancedMillis = 0L
        while (advancedMillis < durationMillis) {
            val step = minOf(16L, durationMillis - advancedMillis)
            compose.mainClock.advanceTimeBy(step, ignoreFrameDuration = true)
            SystemClock.sleep(step)
            advancedMillis += step
        }
    }
}

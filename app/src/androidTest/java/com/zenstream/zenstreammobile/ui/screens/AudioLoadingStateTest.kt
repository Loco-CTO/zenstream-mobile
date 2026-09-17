package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zenstream.zenstreammobile.model.AudioPlayerState
import com.zenstream.zenstreammobile.model.AudioQueueEntry
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioLoadingStateTest {
    @get:Rule val composeRule = createComposeRule()

    private val session = AuthSession("https://example.com", "token", "user", "name")
    private val state =
        AudioPlayerState(
            queue =
                listOf(
                    AudioQueueEntry(
                        entryId = "entry",
                        track = MediaItem("track", "Song", albumArtist = "Artist"),
                    )
                ),
            currentIndex = 0,
            isLoading = true,
        )

    @Test
    fun miniPlayerShowsLoadingAndKeepsStopAvailable() {
        composeRule.setContent {
            ZenStreamTheme {
                AudioMiniPlayerContent(
                    state = state,
                    session = session,
                    onOpenNowPlaying = {},
                    onStop = {},
                    onTogglePlayback = {},
                )
            }
        }

        composeRule.onNodeWithTag("audio-mini-loading-rail").assertExists()
        composeRule.onNodeWithTag("audio-mini-loading").assertExists()
        composeRule.onNodeWithTag("audio-mini-play-pause").assertIsNotEnabled()
        composeRule.onNodeWithTag("audio-mini-stop").assertIsEnabled()
    }

    @Test
    fun loadingNowPlayingScrubberDoesNotExposeSeeking() {
        var seekCalls = 0
        composeRule.setContent {
            ZenStreamTheme {
                AudioProgressScrubber(
                    trackKey = "entry",
                    positionSeconds = 0L,
                    durationSeconds = 0L,
                    isLoading = true,
                    accent = Color.Magenta,
                    onSeek = { seekCalls++ },
                )
            }
        }

        composeRule.onNodeWithTag("audio-now-playing-loading").assertExists()
        composeRule.runOnIdle { assertEquals(0, seekCalls) }
    }

    @Test
    fun miniPlayerIsPresentAsSoonAsAQueuedStateIsPublished() {
        composeRule.setContent {
            ZenStreamTheme {
                AudioMiniPlayerContent(
                    state = state,
                    session = session,
                    onOpenNowPlaying = {},
                    onStop = {},
                    onTogglePlayback = {},
                )
            }
        }

        composeRule.onNodeWithTag("audio-mini-stop").assertExists()
    }
}

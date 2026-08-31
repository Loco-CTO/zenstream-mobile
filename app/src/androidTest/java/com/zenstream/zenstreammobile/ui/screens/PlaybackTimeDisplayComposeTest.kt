package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.PlaybackTimeDisplayMode
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaybackTimeDisplayComposeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun timerTogglesElapsedAndRemainingDisplayOnClick() {
        var mode by mutableStateOf(PlaybackTimeDisplayMode.Remaining)
        composeRule.setContent {
            ZenStreamTheme {
                PlaybackTimeToggle(
                    positionSeconds = 743.0,
                    durationSeconds = 1_440.0,
                    mode = mode,
                    onToggle = { mode = mode.toggled() },
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val timer = composeRule.onNodeWithTag("player-time")
        timer.assertIsDisplayed()
        timer.assertTextEquals("-11:37 / 24:00")
        timer.assertIsToggleable()
        timer.assertIsOff()
        timer.assert(hasContentDescription(context.getString(R.string.player_show_elapsed_time)))

        timer.performClick()

        timer.assertTextEquals("12:23 / 24:00")
        timer.assertIsOn()
        timer.assert(hasContentDescription(context.getString(R.string.player_show_remaining_time)))
        composeRule.runOnIdle {
            assertEquals(PlaybackTimeDisplayMode.Elapsed, mode)
        }
    }
}

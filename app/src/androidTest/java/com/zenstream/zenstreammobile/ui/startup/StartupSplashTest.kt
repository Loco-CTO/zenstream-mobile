package com.zenstream.zenstreammobile.ui.startup

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Rule
import org.junit.Test

class StartupSplashTest {
    @get:Rule val composeRule = createComposeRule()

    private val destinationReady = mutableStateOf(false)
    private val splash =
        SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo.Indeterminate,
        )

    @Test
    fun fastStartupPlaysIntroAndExit() {
        showSplash(ready = true)
        composeRule.mainClock.advanceTimeBy(590)
        composeRule.onNode(splash).assertExists()

        composeRule.onNodeWithText("Destination").assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(260)
        assertDestinationVisible()
    }

    @Test
    fun slowStartupKeepsAnimatingAndExitsWithoutRepeatingIntro() {
        showSplash(ready = false)
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.onNode(splash).assertExists()

        composeRule.runOnIdle { destinationReady.value = true }
        composeRule.mainClock.advanceTimeBy(130)
        composeRule.onNode(splash).assertExists()

        composeRule.mainClock.advanceTimeBy(180)
        assertDestinationVisible()
    }

    @Test
    fun unresolvedStartupReleasesBrandedSurface() {
        showSplash(ready = false)
        composeRule.mainClock.advanceTimeBy(4_100)
        composeRule.onNode(splash).assertExists()

        composeRule.mainClock.advanceTimeBy(800)
        assertDestinationVisible()
    }

    private fun showSplash(ready: Boolean) {
        destinationReady.value = ready
        composeRule.mainClock.autoAdvance = false
        val showing = mutableStateOf(true)
        composeRule.setContent {
            ZenStreamTheme {
                StartupSplash(
                    active = showing.value,
                    destinationReady = destinationReady.value,
                    onFinished = { showing.value = false },
                ) {
                    Text("Destination")
                }
            }
        }
    }

    private fun assertDestinationVisible() {
        composeRule.onNode(splash).assertDoesNotExist()
        composeRule.onNodeWithText("Destination").assertExists()
    }
}

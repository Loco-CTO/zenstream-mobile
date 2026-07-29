package com.zenstream.zenstreammobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntil
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Rule
import org.junit.Test

class ToastTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun stacksToastsAndDismissesTheSelectedMessage() {
        composeRule.setContent {
            ZenStreamTheme {
                val toast = rememberToastHostState()
                LaunchedEffect(Unit) {
                    toast.success("Joined the group")
                    toast.error("Could not join")
                }
                ToastHost(state = toast)
            }
        }

        val dismiss = composeRule.activity.getString(R.string.toast_dismiss)
        composeRule.onNodeWithText("Joined the group").assertIsDisplayed()
        composeRule.onNodeWithText("Could not join").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(dismiss)[0].performClick()
        composeRule.onNodeWithText("Joined the group").assertDoesNotExist()
        composeRule.onNodeWithText("Could not join").assertIsDisplayed()
    }

    @Test
    fun exposesAnAccessibleDismissAction() {
        composeRule.setContent {
            ZenStreamTheme {
                val toast = rememberToastHostState()
                LaunchedEffect(Unit) { toast.success("Syncplay group created") }
                ToastHost(state = toast)
            }
        }

        composeRule.onNodeWithContentDescription(
            composeRule.activity.getString(R.string.toast_dismiss),
        ).assertIsDisplayed()
    }

    @Test
    fun expiresAfterItsConfiguredDuration() {
        composeRule.setContent {
            ZenStreamTheme {
                val toast = rememberToastHostState(durationMillis = 100)
                LaunchedEffect(Unit) { toast.success("Temporary Syncplay notice") }
                ToastHost(state = toast)
            }
        }

        composeRule.onNodeWithText("Temporary Syncplay notice").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 2_000) {
            composeRule.onAllNodesWithText("Temporary Syncplay notice")
                .fetchSemanticsNodes().isEmpty()
        }
    }
}

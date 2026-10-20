package com.zenstream.zenstreammobile.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Rule
import org.junit.Test

class ToastTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

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
        composeRule.onAllNodesWithText("Joined the group").assertCountEquals(0)
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

        composeRule
            .onNodeWithContentDescription(composeRule.activity.getString(R.string.toast_dismiss))
            .assertIsDisplayed()
    }

    @Test
    fun staysAboveModalContentAndCanBeDismissed() {
        composeRule.setContent {
            ZenStreamTheme {
                val toast = rememberToastHostState()
                Dialog(
                    onDismissRequest = {},
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                }
                LaunchedEffect(Unit) { toast.success("Toast over modal") }
                ToastHost(state = toast)
            }
        }

        val dismiss = composeRule.activity.getString(R.string.toast_dismiss)
        composeRule.onNodeWithText("Toast over modal").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(dismiss).performClick()
        composeRule.onAllNodesWithText("Toast over modal").assertCountEquals(0)
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
            composeRule
                .onAllNodesWithText("Temporary Syncplay notice")
                .fetchSemanticsNodes()
                .isEmpty()
        }
    }
}

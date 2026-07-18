package com.zenstream.zenstreammobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.zenstream.zenstreammobile.ui.screens.ServerSetupScreen
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Rule
import org.junit.Test

class AuthScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun serverSetupShowsMaterialForm() {
        composeRule.setContent { ZenStreamTheme { ServerSetupScreen { } } }
        composeRule.onNodeWithText("Connect to ZenStream").assertIsDisplayed()
        composeRule.onNodeWithText("Orchestrator URL").assertIsDisplayed()
        composeRule.onNodeWithText("Continue").assertIsDisplayed()
    }
}

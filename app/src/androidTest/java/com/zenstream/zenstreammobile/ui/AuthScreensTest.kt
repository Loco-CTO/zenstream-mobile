package com.zenstream.zenstreammobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.ui.screens.FEATURE_BAR_ASPECT_RATIO
import com.zenstream.zenstreammobile.ui.screens.FeaturedHero
import com.zenstream.zenstreammobile.ui.screens.ServerSetupScreen
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
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

    @Test
    fun featureBarUsesIconOnlyInfoAction() {
        val item = MediaItem(
            id = "1",
            name = "Example",
            type = "Movie",
            backdropImageTags = listOf("backdrop-tag"),
        )
        val session = AuthSession("https://example.com", "token", "user", "name")

        composeRule.setContent {
            ZenStreamTheme {
                FeaturedHero(
                    items = listOf(item),
                    session = session,
                    onPlay = {},
                    onInfo = {},
                    showEmptyLibrary = false,
                )
            }
        }

        composeRule.onNodeWithContentDescription("Show information for Example")
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("Info").assertCountEquals(0)
    }

    @Test
    fun featureBarUsesSixteenByNineAspectRatio() {
        assertEquals(16f / 9f, FEATURE_BAR_ASPECT_RATIO, 0.001f)
    }
}

package com.zenstream.zenstreammobile.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
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
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.server_setup_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.orchestrator_url)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.continue_label)).assertIsDisplayed()
    }

    @Test
    fun featureBarUsesLogoAndNoActions() {
        val item = MediaItem(
            id = "1",
            name = "Example",
            type = "Movie",
            imageTags = mapOf("Logo" to "logo-tag"),
            backdropImageTags = listOf("backdrop-tag"),
        )
        val session = AuthSession("https://example.com", "token", "user", "name")

        composeRule.setContent {
            ZenStreamTheme {
                FeaturedHero(
                    items = listOf(item),
                    session = session,
                    showEmptyLibrary = false,
                )
            }
        }

        val logoDescription = InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getString(R.string.logo_description, "Example")
        composeRule.onNodeWithContentDescription(logoDescription)
            .assertExists()
        composeRule.onNodeWithContentDescription(logoDescription)
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("Play").assertCountEquals(0)
        composeRule.onAllNodesWithText("Info").assertCountEquals(0)
    }

    @Test
    fun featureBarUsesSixteenByNineAspectRatio() {
        assertEquals(16f / 9f, FEATURE_BAR_ASPECT_RATIO, 0.001f)
    }
}

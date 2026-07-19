package com.zenstream.zenstreammobile.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Rule
import org.junit.Test

class MainTopBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsBrandLogoAndStaticProfileIcon() {
        composeRule.setContent {
            ZenStreamTheme {
                MainTopBar()
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.app_logo_description)
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            context.getString(R.string.profile_description)
        ).assertIsDisplayed()
    }
}

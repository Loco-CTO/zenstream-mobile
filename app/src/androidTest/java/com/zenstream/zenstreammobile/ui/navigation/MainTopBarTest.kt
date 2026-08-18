package com.zenstream.zenstreammobile.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainTopBarTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun showsBrandLogoWithoutSettingsAction() {
        composeRule.setContent {
            ZenStreamTheme {
                MainTopBar()
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.app_logo_description))
            .assertIsDisplayed()
        assertTrue(
            composeRule
                .onAllNodesWithContentDescription(context.getString(R.string.settings_description))
                .fetchSemanticsNodes()
                .isEmpty()
        )
        assertTrue(
            composeRule
                .onAllNodesWithContentDescription(context.getString(R.string.search))
                .fetchSemanticsNodes()
                .isEmpty()
        )
    }

    @Test
    fun searchActionInvokesCallbackWhenEnabled() {
        var searchClicked = false
        composeRule.setContent {
            ZenStreamTheme {
                MainTopBar(showSearchAction = true, onSearch = { searchClicked = true })
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.search))
            .assertIsDisplayed()
            .performClick()
        assertTrue(searchClicked)
    }

    @Test
    fun lucideIconResourcesAreAvailable() {
        listOf(
                LucideR.drawable.lucide_ic_house,
                LucideR.drawable.lucide_ic_search,
                LucideR.drawable.lucide_ic_library,
                LucideR.drawable.lucide_ic_settings,
                LucideR.drawable.lucide_ic_play,
                LucideR.drawable.lucide_ic_pause,
                LucideR.drawable.lucide_ic_arrow_left,
                LucideR.drawable.lucide_ic_heart,
                LucideR.drawable.lucide_ic_captions,
            )
            .forEach { resourceId ->
                assertTrue("Expected a Lucide drawable resource", resourceId != 0)
            }
    }
}

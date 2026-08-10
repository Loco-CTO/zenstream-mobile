package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.BuildConfig
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rootShowsSettingsSectionsVersionAndLogout() {
        var loggedOut = false
        composeRule.setContent {
            ZenStreamTheme {
                SettingsRootContent(onOpenSection = {}, onLogout = { loggedOut = true })
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.player_group)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.subtitles_group)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.settings_version)).assertIsDisplayed()
        composeRule
            .onNodeWithText(
                context.getString(R.string.settings_version_value, BuildConfig.ZENSTREAM_VERSION)
            )
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.logout)).performClick()
        composeRule.runOnIdle { assertTrue(loggedOut) }
    }

    @Test
    fun sectionRowsOpenTheRequestedSubtab() {
        var selected: SettingsSection? = null
        composeRule.setContent {
            ZenStreamTheme {
                SettingsRootContent(onOpenSection = { selected = it }, onLogout = {})
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.player_group)).performClick()
        composeRule.runOnIdle { assertEquals(SettingsSection.Player, selected) }
    }
}

package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.BuildConfig
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.InterfaceLocaleMode
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rootShowsSettingsSectionsFooterVersionAndLogout() {
        var loggedOut = false
        composeRule.setContent {
            ZenStreamTheme {
                SettingsRootContent(onOpenSection = {}, onLogout = { loggedOut = true })
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.player_group)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.subtitles_group)).assertIsDisplayed()
        val versionFooter =
            context.getString(R.string.settings_version_value, BuildConfig.ZENSTREAM_VERSION)
        composeRule.onNodeWithText(versionFooter).assertIsDisplayed()
        composeRule
            .onNodeWithText(versionFooter.substringBefore(BuildConfig.ZENSTREAM_VERSION).trim())
            .assertDoesNotExist()
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

    @Test
    fun interfaceLanguageSelectorReturnsTheChosenDeviceMode() {
        var selected = InterfaceLocaleMode.Automatic
        composeRule.setContent {
            ZenStreamTheme {
                InterfaceLanguageSelector(
                    selected = selected,
                    enabled = true,
                    onChange = { selected = it },
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.interface_language)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.language_japanese)).performClick()
        composeRule.runOnIdle { assertEquals(InterfaceLocaleMode.Japanese, selected) }
    }
}

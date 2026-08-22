package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.BuildConfig
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.InterfaceLocaleMode
import com.zenstream.zenstreammobile.model.SubtitleStyle
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MyPageSettingsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun settingsTabsShowAllGroupsAndOpenRequestedPage() {
        var selected: MyPageSettingsSection? = null
        composeRule.setContent {
            ZenStreamTheme {
                MyPageSettingsTabs(onOpenSection = { selected = it })
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.appearance_group)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.player_group)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.subtitles_group)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.privacy_group)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.updates_group)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.player_group)).performClick()
        composeRule.runOnIdle { assertEquals(MyPageSettingsSection.Player, selected) }
    }

    @Test
    fun calendarEntryUsesTheMyPageRowPatternAndOpensTheCalendar() {
        var opened = false
        composeRule.setContent {
            ZenStreamTheme {
                MyPageCalendarEntry(onOpen = { opened = true })
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.calendar)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.calendar)).performClick()
        composeRule.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun serviceEntriesShowCalendarAndNotificationCenter() {
        var calendarOpened = false
        var notificationsOpened = false
        composeRule.setContent {
            ZenStreamTheme {
                MyPageServiceEntries(
                    onOpenCalendar = { calendarOpened = true },
                    onOpenNotifications = { notificationsOpened = true },
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.calendar)).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.notification_center))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.notification_center)).performClick()
        composeRule.runOnIdle {
            assertTrue(!calendarOpened)
            assertTrue(notificationsOpened)
        }
    }

    @Test
    fun settingsFooterShowsVersionAndLogsOut() {
        var loggedOut = false
        composeRule.setContent {
            ZenStreamTheme {
                MyPageSettingsFooter(onLogout = { loggedOut = true })
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val versionFooter =
            context.getString(R.string.settings_version_value, BuildConfig.ZENSTREAM_VERSION)
        composeRule.onNodeWithText(versionFooter).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.logout)).performClick()
        composeRule.runOnIdle { assertTrue(loggedOut) }
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

    @Test
    fun subtitleColorFieldOpensVisualPickerAndRgbSliderUpdatesColor() {
        var selected by mutableStateOf("#ffffff")
        composeRule.setContent {
            ZenStreamTheme {
                SubtitleColorField(
                    label = "Text color",
                    value = selected,
                    onChange = { selected = it },
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText("Text color").performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.subtitle_color_picker_title, "Text color"))
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(
                context.getString(R.string.subtitle_color_value, "#ffffff")
            )
            .assertIsDisplayed()
        val redChannel =
            context.getString(
                R.string.subtitle_color_slider,
                context.getString(R.string.subtitle_color_red),
            )
        composeRule.onNodeWithContentDescription(redChannel).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(redChannel).performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(128f)
        }
        composeRule.runOnIdle { assertEquals("#80ffff", selected) }
    }

    @Test
    fun subtitleBottomSpacingSliderUsesWholeDpValues() {
        var style by mutableStateOf(SubtitleStyle())
        composeRule.setContent {
            ZenStreamTheme {
                SubtitleSettings(
                    style = style,
                    onChange = { style = it(style) },
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val label = context.getString(R.string.subtitle_bottom_spacing)
        composeRule.onNodeWithText(label).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label).performSemanticsAction(
            SemanticsActions.SetProgress
        ) {
            it(217f)
        }
        composeRule.runOnIdle { assertEquals(217f, style.bottomSpacing) }
    }

    @Test
    fun changePasswordFormValidatesAndCompletes() {
        var submitted = false
        var continueToLogin = false
        composeRule.setContent {
            ZenStreamTheme {
                ChangePasswordForm(
                    onSubmitPasswordChange = { _, _, _ -> submitted = true },
                    onClose = {},
                    onContinueToLogin = { continueToLogin = true },
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fields = composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        fields[0].performTextInput("current-password")
        fields[1].performTextInput("short")
        fields[2].performTextInput("short")
        composeRule.onNodeWithText(context.getString(R.string.save)).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.password_too_short))
            .assertIsDisplayed()

        fields[1].performTextInput("new-password")
        fields[2].performTextInput("new-password")
        composeRule.onNodeWithText(context.getString(R.string.save)).performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertTrue(submitted) }
        composeRule.onNodeWithText(context.getString(R.string.password_changed)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.continue_to_login)).performClick()
        composeRule.runOnIdle { assertTrue(continueToLogin) }
    }
}

package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MyPageScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun profileFallsBackToInitialAndOpensAccountSettings() {
        var clicked = false
        val session = AuthSession("https://server", "token", "user-1", "Miyu")
        composeRule.setContent {
            ZenStreamTheme {
                ProfileCard(session = session, onOpenProfile = { clicked = true })
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText("M").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.account_settings)).performClick()
        composeRule.runOnIdle { assertTrue(clicked) }
    }

    @Test
    fun profileSettingsUsesChangeActionWithoutDuplicateDeleteActionWhenAvatarExists() {
        val session =
            AuthSession(
                "https://server",
                "token",
                "user-1",
                "Miyu",
                avatarVersion = "v-1",
            )
        composeRule.setContent {
            ZenStreamTheme {
                ProfileSettingsPage(
                    session = session,
                    avatarError = null,
                    onBack = {},
                    onEditAvatar = {},
                    onChangePassword = {},
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.change_avatar)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.avatar_delete)).assertDoesNotExist()
    }

    @Test
    fun profileSettingsOffersChangePasswordAction() {
        var clicked = false
        val session = AuthSession("https://server", "token", "user-1", "Miyu")
        composeRule.setContent {
            ZenStreamTheme {
                ProfileSettingsPage(
                    session = session,
                    avatarError = null,
                    onBack = {},
                    onEditAvatar = {},
                    onChangePassword = { clicked = true },
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.change_password)).performClick()
        composeRule.runOnIdle { assertTrue(clicked) }
    }

    @Test
    fun avatarActionSheetOnlyOffersUploadWithoutAnAvatar() {
        var uploaded = false
        var deleted = false
        composeRule.setContent {
            ZenStreamTheme {
                AvatarActionSheet(
                    hasAvatar = false,
                    onDismiss = {},
                    onUpload = { uploaded = true },
                    onDelete = { deleted = true },
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithText(context.getString(R.string.avatar_upload_image))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.avatar_delete)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.avatar_upload_image)).performClick()
        composeRule.runOnIdle {
            assertTrue(uploaded)
            assertTrue(!deleted)
        }
    }

    @Test
    fun avatarActionSheetOffersDeleteOnlyWhenAnAvatarExists() {
        var deleted = false
        composeRule.setContent {
            ZenStreamTheme {
                AvatarActionSheet(
                    hasAvatar = true,
                    onDismiss = {},
                    onUpload = {},
                    onDelete = { deleted = true },
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.avatar_delete)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.avatar_delete)).performClick()
        composeRule.runOnIdle { assertTrue(deleted) }
    }

    @Test
    fun deleteConfirmationSupportsCancelAndConfirm() {
        var dismissed = false
        var confirmed = false
        var dialogVisible by mutableStateOf(true)
        composeRule.setContent {
            ZenStreamTheme {
                if (dialogVisible) {
                    AvatarDeleteConfirmationDialog(
                        deleting = false,
                        onDismiss = {
                            dismissed = true
                            dialogVisible = false
                        },
                        onConfirm = { confirmed = true },
                    )
                }
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithText(context.getString(R.string.avatar_delete_confirmation_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).performClick()
        composeRule.runOnIdle { assertTrue(dismissed) }

        composeRule.runOnIdle { dialogVisible = true }
        composeRule.onNodeWithText(context.getString(R.string.avatar_delete)).performClick()
        composeRule.runOnIdle { assertTrue(confirmed) }
    }
}

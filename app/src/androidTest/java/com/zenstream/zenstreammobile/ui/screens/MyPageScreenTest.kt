package com.zenstream.zenstreammobile.ui.screens

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
    fun profileFallsBackToInitialAndOffersAddAvatar() {
        var clicked = false
        val session = AuthSession("https://server", "token", "user-1", "Miyu")
        composeRule.setContent {
            ZenStreamTheme {
                ProfileCard(session = session, onEditAvatar = { clicked = true })
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText("M").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.add_avatar)).performClick()
        composeRule.runOnIdle { assertTrue(clicked) }
    }

    @Test
    fun profileUsesChangeActionWhenAnAvatarVersionExists() {
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
                ProfileCard(session = session, onEditAvatar = {})
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.change_avatar)).assertIsDisplayed()
    }
}

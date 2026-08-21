package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.NotificationItem
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NotificationsScreenActionsTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun notificationActionsCanMarkAnItemReadAndUnread() {
        var item by
            mutableStateOf(
                NotificationItem(
                    id = "notification-1",
                    kind = "episode",
                    title = "New episode",
                    createdAt = "2026-08-20T23:30:39.090571+00:00",
                )
            )
        val session = AuthSession("https://example.test", "token", "user", "Test")
        var removed = false

        composeRule.setContent {
            ZenStreamTheme {
                NotificationRow(
                    item = item,
                    session = session,
                    onClick = {},
                    onToggleRead = {
                        item = item.copy(readAt = if (item.readAt == null) "local" else null)
                    },
                    onRemove = { removed = true },
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actionsLabel = context.getString(R.string.notifications_actions)
        composeRule.onNodeWithContentDescription(actionsLabel).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.notifications_mark_read))
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertNotNull(item.readAt) }

        composeRule.onNodeWithContentDescription(actionsLabel).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.notifications_mark_unread))
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertNull(item.readAt) }

        composeRule.onNodeWithContentDescription(actionsLabel).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.notifications_remove))
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertTrue(removed) }
    }
}

package com.zenstream.zenstreammobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.SyncplayGroup
import com.zenstream.zenstreammobile.model.SyncplayMember
import com.zenstream.zenstreammobile.model.SyncplayUiState
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SyncplayGroupSheetTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun groupTriggerUsesTheUsersIconAndGroupsAccessibilityLabel() {
        var clicked = false
        composeRule.setContent {
            ZenStreamTheme {
                SyncplayGroupButton(
                    enabled = true,
                    playerContext = false,
                    onClick = { clicked = true },
                )
            }
        }

        val label = composeRule.activity.getString(R.string.syncplay_groups)
        composeRule.onNodeWithContentDescription(label).assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(clicked) }
        assertTrue(LucideR.drawable.lucide_ic_users != 0)
    }

    @Test
    fun browserShowsEmptyStateAndCreateAction() {
        var created = false
        composeRule.setContent {
            ZenStreamTheme {
                SyncplayGroupSheet(
                    state = SyncplayUiState(participantId = "participant"),
                    userId = "host",
                    playerContext = false,
                    onDismiss = {},
                    onCreate = { created = true },
                    onJoin = {},
                    onRemoveMember = {},
                    onControlsChanged = {},
                    onReturnToView = {},
                    onLeave = {},
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.syncplay_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.syncplay_create)).performClick()
        composeRule.runOnIdle { assertTrue(created) }
    }

    @Test
    fun browserJoinsASelectedGroup() {
        var joinedGroupId: String? = null
        val group = syncplayGroup()
        composeRule.setContent {
            ZenStreamTheme {
                SyncplayGroupSheet(
                    state = SyncplayUiState(groups = listOf(group), participantId = "viewer-tab"),
                    userId = "viewer",
                    playerContext = false,
                    onDismiss = {},
                    onCreate = {},
                    onJoin = { joinedGroupId = it },
                    onRemoveMember = {},
                    onControlsChanged = {},
                    onReturnToView = {},
                    onLeave = {},
                )
            }
        }

        composeRule.onNodeWithText(group.name).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.syncplay_join)).performClick()
        composeRule.runOnIdle { assertEquals(group.id, joinedGroupId) }
    }

    @Test
    fun activeHostCanRemoveMembersChangeControlsAndReturnToTheView() {
        val group = syncplayGroup(itemId = "item-1")
        var removedMember: String? = null
        var controls: Boolean? = null
        var returnedGroup: SyncplayGroup? = null
        composeRule.setContent {
            ZenStreamTheme {
                SyncplayGroupSheet(
                    state = SyncplayUiState(
                        groups = listOf(group),
                        active = group,
                        participantId = "host-tab",
                    ),
                    userId = "host",
                    playerContext = true,
                    onDismiss = {},
                    onCreate = {},
                    onJoin = {},
                    onRemoveMember = { removedMember = it },
                    onControlsChanged = { controls = it },
                    onReturnToView = { returnedGroup = it },
                    onLeave = {},
                )
            }
        }

        composeRule.onNodeWithText(composeRule.activity.getString(R.string.syncplay_host)).assertIsDisplayed()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.syncplay_remove)).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.syncplay_allow_controls))
            .assertIsDisplayed()
        composeRule.onNode(isToggleable()).performClick()
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.syncplay_return_to_view))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals("viewer", removedMember)
            assertEquals(group, returnedGroup)
            assertEquals(true, controls)
        }
    }

    private fun syncplayGroup(itemId: String? = null, playing: Boolean = false): SyncplayGroup = SyncplayGroup(
        id = "group-1",
        name = "Movie night",
        hostUserId = "host",
        hostName = "Host",
        allowViewerControls = false,
        itemId = itemId,
        position = 0.0,
        playing = playing,
        resumeWhenReady = false,
        revision = 1,
        timelineRevision = 1,
        mediaGeneration = 1,
        anchorPosition = 0.0,
        anchorServerTime = 0.0,
        effectiveAt = 0.0,
        playbackState = if (playing) "playing" else "paused",
        pauseReason = null,
        hostDisconnectedAt = null,
        updatedAt = 0.0,
        members = listOf(
            SyncplayMember(
                userId = "host",
                participantId = "host-tab",
                username = "Host",
                watchingTogether = true,
                viewing = true,
                loading = false,
                readyGeneration = 1,
                role = "host",
            ),
            SyncplayMember(
                userId = "viewer",
                participantId = "viewer-tab",
                username = "Viewer",
                watchingTogether = true,
                viewing = true,
                loading = false,
                readyGeneration = 1,
                role = "member",
            ),
        ),
    )
}

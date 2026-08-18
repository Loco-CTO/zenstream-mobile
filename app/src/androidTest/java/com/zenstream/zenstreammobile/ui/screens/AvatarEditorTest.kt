package com.zenstream.zenstreammobile.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.CatalogApi
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AvatarEditorTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun editorDoesNotDuplicateParentActions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = testRepository(context)

        composeRule.setContent {
            ZenStreamTheme {
                AvatarEditorDialog(
                    session =
                        AuthSession(
                            "https://server",
                            "token",
                            "user-1",
                            "Miyu",
                            avatarVersion = "v-1",
                        ),
                    repository = repository,
                    onSessionChanged = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.remove_avatar)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(R.string.cancel)).assertDoesNotExist()
    }

    @Test
    fun imagePickerRequestIsDelegatedToTheActivityOwner() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = testRepository(context)
        var pickRequests = 0

        composeRule.setContent {
            ZenStreamTheme {
                AvatarEditorDialog(
                    session = AuthSession("https://server", "token", "user-1", "Miyu"),
                    repository = repository,
                    onSessionChanged = {},
                    onDismiss = {},
                    onPickImage = { pickRequests += 1 },
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.avatar_choose_image)).performClick()
        composeRule.runOnIdle { assertEquals(1, pickRequests) }
    }

    private fun testRepository(context: android.content.Context): CatalogRepository =
        CatalogRepository(
            CatalogApi(),
            SessionStore(
                context,
                dataStoreName = "avatar_editor_test_${System.nanoTime()}",
            ),
        )
}

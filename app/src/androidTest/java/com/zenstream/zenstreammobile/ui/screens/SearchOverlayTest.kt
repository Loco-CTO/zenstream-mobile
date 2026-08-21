package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.SearchDataSource
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SearchOverlayTest {
    @get:Rule val composeRule = createComposeRule()

    private val session = AuthSession("https://example.test", "token", "user", "Test")

    @Test
    fun openingOverlayFocusesTheSearchField() {
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = FakeSearchDataSource { emptyList() },
                    session = session,
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        composeRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun overlayKeepsUnderlyingContentAndDismissesOutsideTheDialog() {
        var dismissed = false
        composeRule.setContent {
            ZenStreamTheme {
                Box(Modifier.fillMaxSize()) {
                    Text("Home content")
                    SearchOverlayScreen(
                        repository = FakeSearchDataSource { emptyList() },
                        session = session,
                        onDismiss = { dismissed = true },
                        onItemClick = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText("Home content").assertIsDisplayed()
        composeRule.onNodeWithTag("search-overlay-transparent").assertIsDisplayed()
        composeRule.onNodeWithTag("search-dialog").performClick()
        assertFalse(dismissed)

        composeRule.onNodeWithTag("search-overlay-transparent").performTouchInput {
            click(Offset(1f, 1f))
        }
        assertTrue(dismissed)
    }

    @Test
    fun twoCharactersUseSolidScrimAndClearingRestoresTransparentScrim() {
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = FakeSearchDataSource { emptyList() },
                    session = session,
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        val field = composeRule.onNode(hasSetTextAction())
        field.performTextInput("d")
        composeRule.onNodeWithTag("search-overlay-transparent").assertIsDisplayed()
        field.performTextInput("u")
        composeRule.onNodeWithTag("search-overlay-solid").assertIsDisplayed()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        composeRule.onNodeWithTag("search-overlay-transparent").assertIsDisplayed()
    }

    @Test
    fun searchDoesNotRequestUntilSubmittedAndNoResultsRemainOnSolidScrim() {
        val source = FakeSearchDataSource { emptyList() }
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = source,
                    session = session,
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        val field = composeRule.onNode(hasSetTextAction())
        field.performTextInput("du")
        assertTrue(source.queries.isEmpty())
        field.performImeAction()
        composeRule.waitUntil(5_000) { source.queries == listOf("du") }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodesWithText(context.getString(R.string.no_search_results))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("search-overlay-solid").assertIsDisplayed()

        composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        composeRule.onNodeWithTag("search-overlay-transparent").assertIsDisplayed()
    }

    @Test
    fun populatedResultsAreShownInsideTheOverlay() {
        val source = FakeSearchDataSource { listOf(MediaItem("dune", "Dune")) }
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = source,
                    session = session,
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        val field = composeRule.onNode(hasSetTextAction())
        field.performTextInput("du")
        field.performImeAction()
        composeRule.waitUntil(5_000) { source.queries == listOf("du") }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Dune").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("search-overlay-solid").assertIsDisplayed()
        composeRule.onAllNodesWithText("Dune").fetchSemanticsNodes().also { nodes ->
            assertTrue(nodes.isNotEmpty())
        }
    }

    @Test
    fun retryReissuesTheSubmittedQueryAfterAnError() {
        var attempts = 0
        val source = FakeSearchDataSource {
            attempts += 1
            if (attempts == 1) error("temporary failure")
            listOf(MediaItem("dune", "Dune"))
        }
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = source,
                    session = session,
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val field = composeRule.onNode(hasSetTextAction())
        field.performTextInput("du")
        field.performImeAction()
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodesWithText(context.getString(R.string.search_load_failed))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        composeRule.waitUntil(5_000) { source.queries == listOf("du", "du") }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Dune").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun selectingAResultDismissesBeforeOpeningTheSelectedItem() {
        val events = mutableListOf<String>()
        val source = FakeSearchDataSource { listOf(MediaItem("dune", "Dune")) }
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = source,
                    session = session,
                    onDismiss = { events += "dismiss" },
                    onItemClick = { events += "select:${it.id}" },
                )
            }
        }

        val field = composeRule.onNode(hasSetTextAction())
        field.performTextInput("du")
        field.performImeAction()
        composeRule.waitUntil(5_000) { source.queries == listOf("du") }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Dune").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Play Dune").performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("dismiss", "select:dune"), events)
        }
    }

    @Test
    fun activeQueryThresholdMatchesTheOverlayScrimContract() {
        assertFalse(isSearchQueryActive(""))
        assertFalse(isSearchQueryActive(" d "))
        assertTrue(isSearchQueryActive(" du "))
    }
}

private class FakeSearchDataSource(private val response: suspend (String) -> List<MediaItem>) :
    SearchDataSource {
    val queries = mutableListOf<String>()

    override suspend fun clearSession() = Unit

    override suspend fun search(session: AuthSession, query: String): List<MediaItem> {
        queries += query
        return response(query)
    }
}

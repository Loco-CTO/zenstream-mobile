package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.zenstream.zenstreammobile.model.PagedSearch
import com.zenstream.zenstreammobile.model.SearchFacets
import com.zenstream.zenstreammobile.model.SearchFilter
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import kotlinx.coroutines.CompletableDeferred
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
                    currentRoute = "home",
                    onDestinationClick = {},
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().config.let { semantics ->
                semantics.contains(SemanticsProperties.Focused) &&
                    semantics[SemanticsProperties.Focused]
            }
        }
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun overlayUsesOpaqueLayoutAndBackButtonDismisses() {
        var dismissed = false
        composeRule.setContent {
            ZenStreamTheme {
                Box(Modifier.fillMaxSize()) {
                    Text("Home content")
                    SearchOverlayScreen(
                        repository = FakeSearchDataSource { emptyList() },
                        session = session,
                        currentRoute = "home",
                        onDestinationClick = {},
                        onDismiss = { dismissed = true },
                        onItemClick = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag("search-overlay-solid").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.back)
            )
            .performClick()
        assertTrue(dismissed)
    }

    @Test
    fun tappingTheOpaqueBackgroundDoesNotDismissSearch() {
        var dismissed = false
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = FakeSearchDataSource { emptyList() },
                    session = session,
                    currentRoute = "home",
                    onDestinationClick = {},
                    onDismiss = { dismissed = true },
                    onItemClick = {},
                )
            }
        }

        composeRule.onNodeWithTag("search-overlay-solid").performTouchInput {
            click(Offset(1f, 1f))
        }

        assertFalse(dismissed)
    }

    @Test
    fun clearingTheQueryKeepsTheOpaqueSearchLayout() {
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = FakeSearchDataSource { emptyList() },
                    session = session,
                    currentRoute = "home",
                    onDestinationClick = {},
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        val field = composeRule.onNode(hasSetTextAction())
        composeRule.onNodeWithTag("search-overlay-solid").assertIsDisplayed()
        field.performTextInput("d")
        composeRule.onNodeWithTag("search-overlay-solid").assertIsDisplayed()
        field.performTextInput("u")
        composeRule.onNodeWithTag("search-overlay-solid").assertIsDisplayed()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        composeRule.onNodeWithTag("search-overlay-solid").assertIsDisplayed()
    }

    @Test
    fun searchRequestsEachTypedQueryWithoutSubmission() {
        val source = FakeSearchDataSource { emptyList() }
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = source,
                    session = session,
                    currentRoute = "home",
                    onDestinationClick = {},
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        val field = composeRule.onNode(hasSetTextAction())
        field.performTextInput("d")
        composeRule.waitUntil(5_000) { source.queries == listOf("d") }
        field.performTextInput("u")
        composeRule.waitUntil(5_000) { source.queries == listOf("d", "du") }
        field.performImeAction()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodesWithText(context.getString(R.string.no_search_results))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("search-overlay-solid").assertIsDisplayed()

        composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        composeRule.onNodeWithTag("search-overlay-solid").assertIsDisplayed()
    }

    @Test
    fun populatedResultsAreShownInsideTheOverlay() {
        val source = FakeSearchDataSource { listOf(MediaItem("dune", "Dune")) }
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = source,
                    session = session,
                    currentRoute = "home",
                    onDestinationClick = {},
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        val field = composeRule.onNode(hasSetTextAction())
        field.performTextInput("d")
        field.performTextInput("u")
        composeRule.waitUntil(5_000) { source.queries == listOf("d", "du") }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Dune").fetchSemanticsNodes().isNotEmpty()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithText(context.getString(R.string.search_result_count, 1))
            .assertIsDisplayed()
        composeRule.onNodeWithTag("search-overlay-solid").assertIsDisplayed()
        composeRule.onAllNodesWithText("Dune").fetchSemanticsNodes().also { nodes ->
            assertTrue(nodes.isNotEmpty())
        }
    }

    @Test
    fun filtersRenderCountsAndSwitchToASeparateRequestStream() {
        val source = FilterSearchDataSource { _, _, _ ->
            val items = listOf(MediaItem("movie", "Dune", type = "Movie"))
            PagedSearch(
                items,
                items.size,
                SearchFacets(all = 2, movie = 1, release = 1),
            )
        }
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = source,
                    session = session,
                    currentRoute = "home",
                    onDestinationClick = {},
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNode(hasSetTextAction()).performTextInput("d")
        composeRule.waitUntil(5_000) { source.filters.contains(SearchFilter.All) }
        composeRule.onNodeWithTag("search-filter-row").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "${context.getString(R.string.search_filter_movie)} 1",
                useUnmergedTree = true,
            )
            .performClick()
        composeRule.waitUntil(5_000) { source.filters.contains(SearchFilter.Movie) }
        assertEquals(listOf(SearchFilter.All, SearchFilter.Movie), source.filters)
    }

    @Test
    fun featuredBackdropIsShownOnceAndMusicRowsUseSquareArtwork() {
        val featured =
            MediaItem(
                "featured",
                "Featured Movie",
                type = "Movie",
                imageTags = mapOf("Primary" to "/api/catalog/items/featured/images/Primary"),
                backdropImageTags = listOf("/api/catalog/items/featured/images/Backdrop"),
            )
        val album =
            MediaItem(
                "album",
                "Featured Album",
                type = "MusicAlbum",
                imageTags = mapOf("Primary" to "/api/catalog/items/album/images/Primary"),
            )
        val source = FilterSearchDataSource { _, _, _ ->
            PagedSearch(
                listOf(featured, album),
                2,
                SearchFacets(all = 2, movie = 1, release = 1),
            )
        }
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = source,
                    session = session,
                    currentRoute = "home",
                    onDestinationClick = {},
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("featured")
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodesWithTag("search-featured-panel")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("search-featured-panel").assertIsDisplayed()
        assertTrue(
            composeRule
                .onAllNodesWithTag("search-result-row-featured")
                .fetchSemanticsNodes()
                .isEmpty()
        )
        composeRule.onNodeWithTag("search-result-row-album").assertIsDisplayed()

        val feature = composeRule.onNodeWithTag("search-featured-panel").getUnclippedBoundsInRoot()
        val music = composeRule.onNodeWithTag("search-artwork-album").getUnclippedBoundsInRoot()
        assertEquals(
            16f / 9f,
            (feature.right - feature.left) / (feature.bottom - feature.top),
            0.08f,
        )
        assertEquals(1f, (music.right - music.left) / (music.bottom - music.top), 0.05f)
    }

    @Test
    fun noBackdropShowsCompactPosterRowsWithoutFeaturedPanel() {
        val movie =
            MediaItem(
                "movie",
                "No Backdrop Movie",
                type = "Movie",
                imageTags = mapOf("Primary" to "/api/catalog/items/movie/images/Primary"),
            )
        val source = FilterSearchDataSource { _, _, _ ->
            PagedSearch(listOf(movie), 1, SearchFacets(all = 1, movie = 1))
        }
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = source,
                    session = session,
                    currentRoute = "home",
                    onDestinationClick = {},
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("movie")
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodesWithTag("search-result-row-movie")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTrue(
            composeRule.onAllNodesWithTag("search-featured-panel").fetchSemanticsNodes().isEmpty()
        )
        composeRule.onNodeWithTag("search-result-row-movie").assertIsDisplayed()

        val poster = composeRule.onNodeWithTag("search-artwork-movie").getUnclippedBoundsInRoot()
        assertEquals(2f / 3f, (poster.right - poster.left) / (poster.bottom - poster.top), 0.05f)
    }

    @Test
    fun gridLoadsOneMorePageNearTheEndAndShowsRetryFooterAfterFailure() {
        var pageTwoAttempts = 0
        val source = PagedSearchDataSource { _, page ->
            when (page) {
                1 -> PagedSearch(listOf(MediaItem("one", "Dune")), 2)
                2 -> {
                    if (pageTwoAttempts++ == 0) error("temporary failure")
                    PagedSearch(listOf(MediaItem("two", "Dune 2")), 2)
                }
                else -> error("unexpected page $page")
            }
        }
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = source,
                    session = session,
                    currentRoute = "home",
                    onDestinationClick = {},
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("d")
        composeRule.waitUntil(5_000) { source.pages == listOf(1, 2) }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodesWithText(context.getString(R.string.library_load_more_failed))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertEquals(1, source.pages.count { it == 2 })

        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        composeRule.waitUntil(5_000) { source.pages.count { it == 2 } == 2 }
        composeRule.onNodeWithText("Dune 2").assertIsDisplayed()
    }

    @Test
    fun resultCountRemainsVisibleWhileTheNextQueryIsPending() {
        val secondResponse = CompletableDeferred<Unit>()
        val source = FakeSearchDataSource { query ->
            if (query == "du") secondResponse.await()
            listOf(MediaItem("dune", "Dune"))
        }
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = source,
                    session = session,
                    currentRoute = "home",
                    onDestinationClick = {},
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val count = context.getString(R.string.search_result_count, 1)
        val field = composeRule.onNode(hasSetTextAction())
        field.performTextInput("d")
        composeRule.waitUntil(5_000) { source.queries == listOf("d") }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Dune").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(count).assertIsDisplayed()

        field.performTextInput("u")
        composeRule.waitUntil(5_000) { source.queries == listOf("d", "du") }
        composeRule.onNodeWithText(count).assertIsDisplayed()
        composeRule.onAllNodesWithText("Dune").fetchSemanticsNodes().also { nodes ->
            assertTrue(nodes.isNotEmpty())
        }

        secondResponse.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(count).assertIsDisplayed()
    }

    @Test
    fun retryReissuesTheTypedQueryAfterAnError() {
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
                    currentRoute = "home",
                    onDestinationClick = {},
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val field = composeRule.onNode(hasSetTextAction())
        field.performTextInput("d")
        composeRule.waitUntil(5_000) {
            composeRule
                .onAllNodesWithText(context.getString(R.string.search_load_failed))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithText(context.getString(R.string.retry)).performClick()
        composeRule.waitUntil(5_000) { source.queries == listOf("d", "d") }
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
                    currentRoute = "home",
                    onDestinationClick = {},
                    onDismiss = { events += "dismiss" },
                    onItemClick = { events += "select:${it.id}" },
                )
            }
        }

        val field = composeRule.onNode(hasSetTextAction())
        field.performTextInput("d")
        field.performTextInput("u")
        composeRule.waitUntil(5_000) { source.queries == listOf("d", "du") }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Dune").fetchSemanticsNodes().isNotEmpty()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.play_description, "Dune"))
            .performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("dismiss", "select:dune"), events)
        }
    }

    @Test
    fun bottomNavigationRemainsAvailableAndReportsTheSelectedTab() {
        val selectedRoutes = mutableListOf<String>()
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = FakeSearchDataSource { emptyList() },
                    session = session,
                    currentRoute = "home",
                    onDestinationClick = { selectedRoutes += it },
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.home)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.my_lists)).performClick()

        composeRule.runOnIdle {
            assertEquals(listOf("home", "favorites"), selectedRoutes)
        }
    }
}

private class FakeSearchDataSource(private val response: suspend (String) -> List<MediaItem>) :
    SearchDataSource {
    val queries = mutableListOf<String>()

    override suspend fun clearSession() = Unit

    override suspend fun search(session: AuthSession, query: String, page: Int): PagedSearch {
        queries += query
        val items = response(query)
        return PagedSearch(items, items.size)
    }
}

private class PagedSearchDataSource(private val response: suspend (String, Int) -> PagedSearch) :
    SearchDataSource {
    val pages = mutableListOf<Int>()

    override suspend fun clearSession() = Unit

    override suspend fun search(session: AuthSession, query: String, page: Int): PagedSearch {
        pages += page
        return response(query, page)
    }
}

private class FilterSearchDataSource(
    private val response: suspend (String, Int, SearchFilter) -> PagedSearch
) : SearchDataSource {
    val filters = mutableListOf<SearchFilter>()

    override suspend fun clearSession() = Unit

    override suspend fun search(session: AuthSession, query: String, page: Int): PagedSearch =
        response(query, page, SearchFilter.All)

    override suspend fun search(
        session: AuthSession,
        query: String,
        page: Int,
        filter: SearchFilter,
    ): PagedSearch {
        filters += filter
        return response(query, page, filter)
    }
}

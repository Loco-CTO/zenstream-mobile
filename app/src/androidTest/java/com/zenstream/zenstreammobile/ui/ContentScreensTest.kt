package com.zenstream.zenstreammobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.LibraryDataSource
import com.zenstream.zenstreammobile.data.SearchDataSource
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaRow
import com.zenstream.zenstreammobile.model.PagedLibrary
import com.zenstream.zenstreammobile.model.RowTitle
import com.zenstream.zenstreammobile.ui.components.MediaCard
import com.zenstream.zenstreammobile.ui.components.MediaRowView
import com.zenstream.zenstreammobile.ui.components.POSTER_CARD_MAX_WIDTH
import com.zenstream.zenstreammobile.ui.components.POSTER_CARD_MIN_WIDTH
import com.zenstream.zenstreammobile.ui.screens.LibraryScreen
import com.zenstream.zenstreammobile.ui.screens.SearchOverlayScreen
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ContentScreensTest {
    @get:Rule val composeRule = createComposeRule()

    private val session = AuthSession("https://example.test", "token", "user", "Test")

    @Test
    fun searchShowsSearchFieldBeforeAQueryIsEntered() {
        composeRule.setContent {
            ZenStreamTheme {
                SearchOverlayScreen(
                    repository = EmptySearchSource(),
                    session = session,
                    onDismiss = {},
                    onItemClick = {},
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithText(context.getString(R.string.search_placeholder))
            .assertIsDisplayed()
    }

    @Test
    fun libraryShowsSupportedLibraryTabsAndSortControl() {
        val shows = Library("shows", "Shows", "tvshows")
        val movies = Library("movies", "Movies", "movies")
        composeRule.setContent {
            ZenStreamTheme {
                LibraryScreen(
                    LibraryScreenSource(listOf(shows, movies), shows),
                    session,
                    PaddingValues(),
                ) {}
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Movies").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Shows").assertIsDisplayed()
        composeRule.onNodeWithText("Movies").assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(
                InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .getString(R.string.sort_by)
            )
            .assertIsDisplayed()
    }

    @Test
    fun adaptivePosterGridFitsCardsToAvailableWidth() {
        val items = (1..5).map { MediaItem("item-$it", "Item $it") }
        val narrowBounds = renderPosterGrid(items, 360)
        val tabletBounds = renderPosterGrid(items, 800)

        assertEquals(2, narrowBounds.map { it.left }.distinct().size)
        assertEquals(5, tabletBounds.map { it.left }.distinct().size)
        items.forEach { item ->
            val bounds =
                composeRule
                    .onNodeWithContentDescription("Play ${item.name}")
                    .getUnclippedBoundsInRoot()
            val width = bounds.right - bounds.left
            assertTrue(width >= POSTER_CARD_MIN_WIDTH)
            assertTrue(width <= POSTER_CARD_MAX_WIDTH)
        }
    }

    @Test
    fun mediaCardsDoNotShowFollowAction() {
        composeRule.setContent {
            ZenStreamTheme {
                MediaCard(
                    item = MediaItem("movie", "Movie", type = "Movie", following = true),
                    session = session,
                    wide = false,
                    onClick = {},
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.unfollow))
            .assertDoesNotExist()
    }

    @Test
    fun homeRowWithDuplicateMediaIdsDoesNotCrashLazyRowMeasurement() {
        composeRule.setContent {
            ZenStreamTheme {
                MediaRowView(
                    row =
                        MediaRow(
                            title = RowTitle.NewlyAdded,
                            items =
                                listOf(
                                    MediaItem("duplicate", "Duplicate"),
                                    MediaItem("duplicate", "Duplicate"),
                                ),
                        ),
                    session = session,
                    onItemClick = {},
                )
            }
        }

        composeRule.waitForIdle()
    }

    private fun renderPosterGrid(
        items: List<MediaItem>,
        width: Int,
    ): List<androidx.compose.ui.unit.DpRect> {
        composeRule.setContent {
            ZenStreamTheme {
                Box(Modifier.requiredWidth(width.dp).requiredHeight(500.dp)) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = POSTER_CARD_MIN_WIDTH),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(items) { item ->
                            Box(
                                Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                MediaCard(
                                    item,
                                    session,
                                    wide = false,
                                    onClick = {},
                                    gridCard = true,
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return items.map { item ->
            composeRule.onNodeWithContentDescription("Play ${item.name}").getUnclippedBoundsInRoot()
        }
    }
}

private class EmptySearchSource : SearchDataSource {
    override suspend fun clearSession() = Unit

    override suspend fun search(session: AuthSession, query: String) = emptyList<MediaItem>()
}

private class LibraryScreenSource(
    private val available: List<Library>,
    private val selected: Library,
) : LibraryDataSource {
    override suspend fun clearSession() = Unit

    override suspend fun libraries(session: AuthSession) = available

    override suspend fun libraryPage(
        session: AuthSession,
        library: Library,
        startIndex: Int,
        limit: Int,
        sort: LibrarySort,
    ) = PagedLibrary(library, listOf(MediaItem("item", selected.name)), 1)

    override suspend fun cachedLibrarySort(userId: String, libraryId: String) = null

    override suspend fun saveLibrarySort(userId: String, libraryId: String, sort: LibrarySort) =
        Unit
}

package com.zenstream.zenstreammobile.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.LibraryDataSource
import com.zenstream.zenstreammobile.data.SearchDataSource
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.PagedLibrary
import com.zenstream.zenstreammobile.ui.screens.LibraryScreen
import com.zenstream.zenstreammobile.ui.screens.SearchScreen
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Rule
import org.junit.Test

class ContentScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val session = AuthSession("https://example.test", "token", "user", "Test")

    @Test
    fun searchShowsSearchFieldBeforeAQueryIsEntered() {
        composeRule.setContent {
            ZenStreamTheme {
                SearchScreen(EmptySearchSource(), session, PaddingValues()) { }
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.search_placeholder))
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
                ) { }
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Movies").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Shows").assertIsDisplayed()
        composeRule.onNodeWithText("Movies").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.sort_by)
        ).assertIsDisplayed()
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
    override suspend fun saveLibrarySort(userId: String, libraryId: String, sort: LibrarySort) = Unit
}

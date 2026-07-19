package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.data.LibraryDataSource
import com.zenstream.zenstreammobile.data.SearchDataSource
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.LibrarySortBy
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.PagedLibrary
import com.zenstream.zenstreammobile.model.SortOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchLibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val session = AuthSession("https://example.test", "token", "user", "Test")

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun searchRanksExactAndPrefixMatchesBeforeOtherMatches() {
        val items = listOf(
            MediaItem("contains", "The Dune Story"),
            MediaItem("exact", "Dune"),
            MediaItem("prefix", "Dune World"),
        )

        assertEquals(
            listOf("exact", "prefix", "contains"),
            rankSearchResults(items, " dune ").map { it.id },
        )
    }

    @Test
    fun searchDebouncesShortQueriesAndPublishesResults() = runTest {
        val source = FakeSearchDataSource { listOf(MediaItem("dune", "Dune")) }
        val viewModel = SearchViewModel(source, session)

        viewModel.updateQuery("d")
        advanceUntilIdle()
        assertTrue(source.queries.isEmpty())

        viewModel.updateQuery("du")
        runCurrent()
        assertTrue(source.queries.isEmpty())
        advanceTimeBy(299)
        runCurrent()
        assertTrue(source.queries.isEmpty())
        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(listOf("du"), source.queries)
        assertEquals(listOf("dune"), viewModel.uiState.value.results.map { it.id })
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun libraryLoadsNextPageAndRemovesDuplicateItems() = runTest {
        val shows = Library("shows", "Shows", "tvshows")
        val source = FakeLibraryDataSource(
            libraries = listOf(shows),
            pages = mapOf(
                0 to PagedLibrary(shows, listOf(MediaItem("one", "One"), MediaItem("two", "Two")), 3),
                2 to PagedLibrary(shows, listOf(MediaItem("two", "Two"), MediaItem("three", "Three")), 3),
            ),
        )
        val viewModel = LibraryViewModel(source, session)
        advanceUntilIdle()

        assertEquals(listOf("one", "two"), viewModel.uiState.value.items.map { it.id })
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(listOf("one", "two", "three"), viewModel.uiState.value.items.map { it.id })
        assertFalse(viewModel.uiState.value.loadingMore)
        assertFalse(viewModel.uiState.value.loadMoreError)
    }

    @Test
    fun librarySortIsRestoredAndChangesReloadAndPersist() = runTest {
        val shows = Library("shows", "Shows", "tvshows")
        val restored = LibrarySort(LibrarySortBy.SortName, SortOrder.Ascending)
        val source = FakeLibraryDataSource(
            libraries = listOf(shows),
            storedSort = restored,
            pages = mapOf(0 to PagedLibrary(shows, listOf(MediaItem("one", "One")), 1)),
        )
        val viewModel = LibraryViewModel(source, session)
        advanceUntilIdle()
        assertEquals(restored, viewModel.uiState.value.sort)

        val nextSort = LibrarySort(LibrarySortBy.ProductionYear, SortOrder.Descending)
        viewModel.setSort(nextSort)
        advanceUntilIdle()

        assertEquals(nextSort, viewModel.uiState.value.sort)
        assertEquals(nextSort, source.savedSort)
        assertEquals(nextSort, source.requestedSorts.last())
    }
}

private class FakeSearchDataSource(
    private val response: suspend (String) -> List<MediaItem>,
) : SearchDataSource {
    val queries = mutableListOf<String>()

    override suspend fun clearSession() = Unit

    override suspend fun search(session: AuthSession, query: String): List<MediaItem> {
        queries += query
        return response(query)
    }
}

private class FakeLibraryDataSource(
    private val libraries: List<Library>,
    private val pages: Map<Int, PagedLibrary>,
    private val storedSort: LibrarySort? = null,
) : LibraryDataSource {
    var savedSort: LibrarySort? = null
    val requestedSorts = mutableListOf<LibrarySort>()

    override suspend fun clearSession() = Unit

    override suspend fun libraries(session: AuthSession): List<Library> = libraries

    override suspend fun libraryPage(
        session: AuthSession,
        library: Library,
        startIndex: Int,
        limit: Int,
        sort: LibrarySort,
    ): PagedLibrary {
        requestedSorts += sort
        return pages[startIndex] ?: PagedLibrary(library, emptyList(), 0)
    }

    override suspend fun cachedLibrarySort(userId: String, libraryId: String): LibrarySort? = storedSort

    override suspend fun saveLibrarySort(userId: String, libraryId: String, sort: LibrarySort) {
        savedSort = sort
    }
}

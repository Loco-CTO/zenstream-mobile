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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
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
        val items =
            listOf(
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
    fun searchStartsImmediatelyForEveryNonBlankQuery() = runTest {
        val source = FakeSearchDataSource { listOf(MediaItem("dune", "Dune")) }
        val viewModel = SearchViewModel(source, session)

        viewModel.updateQuery("d")
        runCurrent()
        assertEquals(listOf("d"), source.queries)

        viewModel.updateQuery("du")
        runCurrent()
        assertEquals(listOf("d", "du"), source.queries)

        assertEquals(listOf("dune"), viewModel.uiState.value.results.map { it.id })
        assertEquals("du", viewModel.uiState.value.resultQuery)
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun searchRetainsCompletedResultsAndIgnoresOutOfOrderResponses() = runTest {
        val firstResponse = CompletableDeferred<List<MediaItem>>()
        val secondResponse = CompletableDeferred<List<MediaItem>>()
        var request = 0
        val source = FakeSearchDataSource { _ ->
            val response = if (request++ == 0) firstResponse else secondResponse
            withContext(NonCancellable) { response.await() }
        }
        val viewModel = SearchViewModel(source, session)

        viewModel.updateQuery("d")
        runCurrent()
        viewModel.updateQuery("du")
        runCurrent()

        assertEquals(listOf("d", "du"), source.queries)
        assertTrue(viewModel.uiState.value.loading)

        secondResponse.complete(listOf(MediaItem("new", "New result")))
        runCurrent()
        assertEquals("du", viewModel.uiState.value.resultQuery)
        assertEquals(listOf("new"), viewModel.uiState.value.results.map { it.id })
        assertFalse(viewModel.uiState.value.loading)

        firstResponse.complete(listOf(MediaItem("old", "Old result")))
        advanceUntilIdle()
        assertEquals("du", viewModel.uiState.value.resultQuery)
        assertEquals(listOf("new"), viewModel.uiState.value.results.map { it.id })
    }

    @Test
    fun blankQueryClearsResultsWithoutIssuingAnotherRequest() = runTest {
        val source = FakeSearchDataSource { listOf(MediaItem("dune", "Dune")) }
        val viewModel = SearchViewModel(source, session)

        viewModel.updateQuery("d")
        advanceUntilIdle()
        viewModel.updateQuery("   ")
        runCurrent()

        assertEquals(listOf("d"), source.queries)
        assertTrue(viewModel.uiState.value.results.isEmpty())
        assertEquals("", viewModel.uiState.value.resultQuery)
        assertFalse(viewModel.uiState.value.loading)
        assertFalse(viewModel.uiState.value.error)
    }

    @Test
    fun failedQueryRetainsLastSuccessfulResultsAndSetsRetryState() = runTest {
        var request = 0
        val source = FakeSearchDataSource {
            if (request++ == 0) {
                listOf(MediaItem("dune", "Dune"))
            } else {
                error("search failed")
            }
        }
        val viewModel = SearchViewModel(source, session)

        viewModel.updateQuery("d")
        advanceUntilIdle()
        viewModel.updateQuery("du")
        advanceUntilIdle()

        assertEquals(listOf("d", "du"), source.queries)
        assertTrue(viewModel.uiState.value.error)
        assertEquals("d", viewModel.uiState.value.resultQuery)
        assertEquals(listOf("dune"), viewModel.uiState.value.results.map { it.id })
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun libraryLoadsNextPageAndRemovesDuplicateItems() = runTest {
        val shows = Library("shows", "Shows", "tvshows")
        val source =
            FakeLibraryDataSource(
                libraries = listOf(shows),
                pages =
                    mapOf(
                        0 to
                            PagedLibrary(
                                shows,
                                listOf(MediaItem("one", "One"), MediaItem("two", "Two")),
                                3,
                            ),
                        2 to
                            PagedLibrary(
                                shows,
                                listOf(MediaItem("two", "Two"), MediaItem("three", "Three")),
                                3,
                            ),
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
    fun libraryStaysLoadingUntilInitialPageCompletes() = runTest {
        val shows = Library("shows", "Shows", "tvshows")
        val pageGate = CompletableDeferred<Unit>()
        val source =
            FakeLibraryDataSource(
                libraries = listOf(shows),
                pages = mapOf(0 to PagedLibrary(shows, listOf(MediaItem("one", "One")), 1)),
                pageGate = pageGate,
            )
        val viewModel = LibraryViewModel(source, session)

        runCurrent()

        assertTrue(viewModel.uiState.value.loading)
        assertEquals(shows, viewModel.uiState.value.selected)
        assertTrue(viewModel.uiState.value.items.isEmpty())

        pageGate.complete(Unit)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.loading)
        assertEquals(listOf("one"), viewModel.uiState.value.items.map { it.id })
    }

    @Test
    fun librarySortIsRestoredAndChangesReloadAndPersist() = runTest {
        val shows = Library("shows", "Shows", "tvshows")
        val restored = LibrarySort(LibrarySortBy.Title, SortOrder.Ascending)
        val source =
            FakeLibraryDataSource(
                libraries = listOf(shows),
                storedSorts = mapOf(shows.id to restored),
                pages = mapOf(0 to PagedLibrary(shows, listOf(MediaItem("one", "One")), 1)),
            )
        val viewModel = LibraryViewModel(source, session)
        advanceUntilIdle()
        assertEquals(restored, viewModel.uiState.value.sort)

        val nextSort = LibrarySort(LibrarySortBy.Release, SortOrder.Descending)
        viewModel.setSort(nextSort)
        advanceUntilIdle()

        assertEquals(nextSort, viewModel.uiState.value.sort)
        assertEquals(nextSort, source.savedSort)
        assertEquals(nextSort, source.requestedSorts.last())
    }

    @Test
    fun librarySortIsRestoredIndependentlyForEachLibrary() = runTest {
        val shows = Library("shows", "Shows", "tvshows")
        val movies = Library("movies", "Movies", "movies")
        val showsSort = LibrarySort(LibrarySortBy.Title, SortOrder.Ascending)
        val moviesSort = LibrarySort(LibrarySortBy.Runtime, SortOrder.Descending)
        val source =
            FakeLibraryDataSource(
                libraries = listOf(shows, movies),
                storedSorts = mapOf(shows.id to showsSort),
                pages = mapOf(0 to PagedLibrary(shows, listOf(MediaItem("show", "Show")), 1)),
            )
        val viewModel = LibraryViewModel(source, session)
        advanceUntilIdle()
        assertEquals(showsSort, viewModel.uiState.value.sort)

        viewModel.select(movies)
        advanceUntilIdle()
        assertEquals(
            LibrarySort(LibrarySortBy.Added, SortOrder.Descending),
            viewModel.uiState.value.sort,
        )
        viewModel.setSort(moviesSort)
        advanceUntilIdle()

        viewModel.select(shows)
        advanceUntilIdle()
        assertEquals(showsSort, viewModel.uiState.value.sort)
        assertEquals(moviesSort, source.savedSorts["movies"])
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

private class FakeLibraryDataSource(
    private val libraries: List<Library>,
    private val pages: Map<Int, PagedLibrary>,
    private val storedSorts: Map<String, LibrarySort> = emptyMap(),
    private val pageGate: CompletableDeferred<Unit>? = null,
) : LibraryDataSource {
    var savedSort: LibrarySort? = null
    val savedSorts = mutableMapOf<String, LibrarySort>()
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
        pageGate?.await()
        return pages[startIndex] ?: PagedLibrary(library, emptyList(), 0)
    }

    override suspend fun cachedLibrarySort(userId: String, libraryId: String): LibrarySort? =
        storedSorts[libraryId]

    override suspend fun saveLibrarySort(userId: String, libraryId: String, sort: LibrarySort) {
        savedSort = sort
        savedSorts[libraryId] = sort
    }
}

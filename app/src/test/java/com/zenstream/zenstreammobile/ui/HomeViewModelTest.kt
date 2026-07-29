package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.data.HomeDataSource
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibraryData
import com.zenstream.zenstreammobile.model.DerivedHomeData
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaRow
import com.zenstream.zenstreammobile.model.RowTitle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
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
    fun rendersEachSectionAsItsOwnRequestCompletes() = runTest {
        val featured = CompletableDeferred<List<MediaItem>>()
        val continueWatching = CompletableDeferred<List<MediaItem>>()
        val nextUp = CompletableDeferred<List<MediaItem>>()
        val source = FakeHomeDataSource(
            featuredRequest = { featured.await() },
            continueWatchingRequest = { continueWatching.await() },
            nextUpRequest = { nextUp.await() },
        )
        val viewModel = HomeViewModel(source, session)
        runCurrent()

        nextUp.complete(listOf(MediaItem("next", "Next")))
        runCurrent()
        assertEquals(listOf(RowTitle.NextUp), viewModel.uiState.value.data?.rows?.map { it.title })
        assertTrue(viewModel.uiState.value.loading)

        featured.complete(listOf(MediaItem("featured", "Featured", backdropImageTags = listOf("tag"))))
        continueWatching.complete(listOf(MediaItem("continue", "Continue")))
        advanceUntilIdle()

        assertEquals("Featured", viewModel.uiState.value.data?.featured?.single()?.name)
        assertEquals(
            listOf(RowTitle.ContinueWatching, RowTitle.NextUp),
            viewModel.uiState.value.data?.rows?.map { it.title },
        )
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun libraryRowsAppearAsEachLibraryRequestCompletes() = runTest {
        val first = Library("first", "First", "movies")
        val second = Library("second", "Second", "movies")
        val firstData = CompletableDeferred<LibraryData>()
        val secondData = CompletableDeferred<LibraryData>()
        val source = FakeHomeDataSource(
            librariesRequest = { listOf(first, second) },
            libraryDataRequest = { library -> if (library == first) firstData.await() else secondData.await() },
        )
        val viewModel = HomeViewModel(source, session)
        runCurrent()

        secondData.complete(LibraryData(second, listOf(MediaRow(RowTitle.NewlyAdded, second.name, listOf(MediaItem("second", "Second"))))))
        runCurrent()
        assertEquals(listOf("Second"), viewModel.uiState.value.data?.rows?.map { it.items.single().name })

        firstData.complete(LibraryData(first, listOf(MediaRow(RowTitle.NewlyAdded, first.name, listOf(MediaItem("first", "First"))))))
        advanceUntilIdle()
        assertEquals(listOf("First", "Second"), viewModel.uiState.value.data?.rows?.map { it.items.single().name })
    }

    @Test
    fun recentlyPlayedAndGenreRowsFollowLibraryRowsAsTheirRequestCompletes() = runTest {
        val derived = CompletableDeferred<DerivedHomeData>()
        val library = Library("movies", "Movies", "movies")
        val source = FakeHomeDataSource(
            derivedRequest = { derived.await() },
            librariesRequest = { listOf(library) },
            libraryDataRequest = {
                LibraryData(it, listOf(MediaRow(RowTitle.NewlyAdded, it.name, listOf(MediaItem("new", "New")))))
            },
        )
        val viewModel = HomeViewModel(source, session)
        runCurrent()

        derived.complete(
            DerivedHomeData(
                myList = listOf(MediaItem("favorite", "Favorite")),
                recentlyPlayed = listOf(MediaItem("recent", "Recent")),
                genreRows = listOf(
                    MediaRow(RowTitle.Genre, items = listOf(MediaItem("drama", "Drama")), label = "Drama", key = "genre:drama"),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf(RowTitle.MyList, RowTitle.NewlyAdded, RowTitle.RecentlyPlayed, RowTitle.Genre),
            viewModel.uiState.value.data?.rows?.map { it.title },
        )
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun metadataPreferenceRefreshForcesANewHomeRequest() = runTest {
        val source = FakeHomeDataSource(featuredRequest = {
            listOf(MediaItem("before", "Before", backdropImageTags = listOf("tag")))
        })
        val viewModel = HomeViewModel(source, session)
        advanceUntilIdle()

        source.featuredRequest = {
            listOf(MediaItem("after", "Fallback title", backdropImageTags = listOf("tag")))
        }
        source.publishMetadataRefresh()
        advanceUntilIdle()

        assertEquals(2, source.featuredRequests)
        assertEquals("Fallback title", viewModel.uiState.value.data?.featured?.single()?.name)
    }
}

private class FakeHomeDataSource(
    var featuredRequest: suspend () -> List<MediaItem> = { emptyList() },
    var continueWatchingRequest: suspend () -> List<MediaItem> = { emptyList() },
    var nextUpRequest: suspend () -> List<MediaItem> = { emptyList() },
    var derivedRequest: suspend () -> DerivedHomeData = { DerivedHomeData() },
    var librariesRequest: suspend () -> List<Library> = { emptyList() },
    var libraryDataRequest: suspend (Library) -> LibraryData = { LibraryData(it, emptyList()) },
) : HomeDataSource {
    var featuredRequests = 0
    private val _catalogRefreshRevision = MutableStateFlow(0L)
    override val catalogRefreshRevision: StateFlow<Long> = _catalogRefreshRevision

    override suspend fun clearSession() = Unit

    override suspend fun homeFeatured(session: AuthSession): List<MediaItem> = featuredRequest().also { featuredRequests += 1 }
    override suspend fun homeContinueWatching(session: AuthSession): List<MediaItem> = continueWatchingRequest()
    override suspend fun homeNextUp(session: AuthSession): List<MediaItem> = nextUpRequest()
    override suspend fun homeDerived(session: AuthSession): DerivedHomeData = derivedRequest()
    override suspend fun homeLibraries(session: AuthSession): List<Library> = librariesRequest()
    override suspend fun homeLibraryData(session: AuthSession, library: Library): LibraryData = libraryDataRequest(library)

    fun publishMetadataRefresh() {
        _catalogRefreshRevision.value += 1
    }
}

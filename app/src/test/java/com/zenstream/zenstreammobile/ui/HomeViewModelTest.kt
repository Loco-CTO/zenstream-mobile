package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.data.HomeDataSource
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibraryData
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
    fun publishesCompletedSectionBeforeSlowerSectionsFinish() = runTest {
        val featured = MediaItem("featured", "Featured", backdropImageTags = listOf("tag"))
        val source = FakeHomeDataSource()
        val continueGate = CompletableDeferred<List<MediaItem>>()
        source.featuredRequest = { listOf(featured) }
        source.continueRequest = { continueGate.await() }
        source.nextUpRequest = { continueGate.await() }
        source.librariesRequest = { continueGate.await().let { emptyList() } }

        val viewModel = HomeViewModel(source, session)
        runCurrent()

        assertEquals(listOf(featured), viewModel.uiState.value.data?.featured)
        assertTrue(viewModel.uiState.value.loading)
        assertFalse(viewModel.uiState.value.error)

        continueGate.complete(emptyList())
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.loading)
    }

    @Test
    fun failedSectionDoesNotCancelSuccessfulSectionsAndLibrariesKeepOrder() = runTest {
        val firstLibrary = Library("first", "First", "movies")
        val secondLibrary = Library("second", "Second", "movies")
        val firstGate = CompletableDeferred<LibraryData>()
        val secondGate = CompletableDeferred<LibraryData>()
        val source = FakeHomeDataSource(
            featuredRequest = { error("featured failed") },
            continueRequest = { listOf(MediaItem("continue", "Continue")) },
            nextUpRequest = { error("next up failed") },
            librariesRequest = { listOf(firstLibrary, secondLibrary) },
            libraryDataRequest = { library ->
                when (library.id) {
                    firstLibrary.id -> firstGate.await()
                    else -> secondGate.await()
                }
            },
        )
        val viewModel = HomeViewModel(source, session)
        runCurrent()

        assertEquals("Continue", viewModel.uiState.value.data?.rows?.single()?.items?.single()?.name)
        assertTrue(viewModel.uiState.value.loading)

        secondGate.complete(libraryData(secondLibrary, "second-row"))
        runCurrent()
        assertEquals(listOf("Second"), viewModel.uiState.value.data?.rows?.drop(1)?.map { it.libraryName })

        firstGate.complete(libraryData(firstLibrary, "first-row"))
        advanceUntilIdle()
        assertEquals(
            listOf("First", "Second"),
            viewModel.uiState.value.data?.rows?.drop(1)?.map { it.libraryName },
        )
        assertFalse(viewModel.uiState.value.error)
    }

    @Test
    fun globalRowsKeepTheirDesignOrderWhenResponsesCompleteOutOfOrder() = runTest {
        val continueGate = CompletableDeferred<List<MediaItem>>()
        val source = FakeHomeDataSource(
            continueRequest = { continueGate.await() },
            nextUpRequest = { listOf(MediaItem("next", "Next")) },
            librariesRequest = { emptyList() },
        )
        val viewModel = HomeViewModel(source, session)
        runCurrent()

        assertEquals(listOf(RowTitle.NextUp), viewModel.uiState.value.data?.rows?.map { it.title })

        continueGate.complete(listOf(MediaItem("continue", "Continue")))
        advanceUntilIdle()

        assertEquals(
            listOf(RowTitle.ContinueWatching, RowTitle.NextUp),
            viewModel.uiState.value.data?.rows?.map { it.title },
        )
    }

    @Test
    fun allFailuresShowErrorAndRetryClearsTheFailedState() = runTest {
        val source = FakeHomeDataSource(
            featuredRequest = { error("featured failed") },
            continueRequest = { error("continue failed") },
            nextUpRequest = { error("next up failed") },
            librariesRequest = { error("libraries failed") },
        )
        val viewModel = HomeViewModel(source, session)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.loading)
        assertEquals(null, viewModel.uiState.value.data)

        val refreshed = MediaItem("refreshed", "Refreshed", backdropImageTags = listOf("tag"))
        source.featuredRequest = { listOf(refreshed) }
        source.continueRequest = { emptyList() }
        source.nextUpRequest = { emptyList() }
        source.librariesRequest = { emptyList() }
        viewModel.load()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.error)
        assertEquals(listOf(refreshed), viewModel.uiState.value.data?.featured)
    }

    private fun libraryData(library: Library, itemId: String) = LibraryData(
        library,
        listOf(
            MediaRow(
                title = RowTitle.NewlyAdded,
                libraryName = library.name,
                items = listOf(MediaItem(itemId, itemId)),
            )
        ),
    )
}

private class FakeHomeDataSource(
    var featuredRequest: suspend () -> List<MediaItem> = { emptyList() },
    var continueRequest: suspend () -> List<MediaItem> = { emptyList() },
    var nextUpRequest: suspend () -> List<MediaItem> = { emptyList() },
    var librariesRequest: suspend () -> List<Library> = { emptyList() },
    var libraryDataRequest: suspend (Library) -> LibraryData = { library -> LibraryData(library, emptyList()) },
) : HomeDataSource {
    override suspend fun clearSession() = Unit
    override suspend fun homeFeatured(session: AuthSession) = featuredRequest()
    override suspend fun homeContinueWatching(session: AuthSession) = continueRequest()
    override suspend fun homeNextUp(session: AuthSession) = nextUpRequest()
    override suspend fun homeLibraries(session: AuthSession) = librariesRequest()
    override suspend fun homeLibraryData(session: AuthSession, library: Library) = libraryDataRequest(library)
}

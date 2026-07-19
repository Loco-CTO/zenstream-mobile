package com.zenstream.zenstreammobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.LibrarySortBy
import com.zenstream.zenstreammobile.model.SortOrder

class JellyfinApiTest {
    @Test
    fun latestItemsQueryIsAuthenticatedAndImageEnabled() {
        val query = JellyfinApi().latestItemsQuery("user-123")

        assertEquals("user-123", query["userId"])
        assertEquals("true", query["recursive"])
        assertEquals("Series,Movie", query["includeItemTypes"])
        assertEquals("true", query["enableImages"])
        assertEquals("1", query["imageTypeLimit"])
        assertEquals("Primary,Backdrop,Logo,Thumb", query["enableImageTypes"])
        assertTrue(query["fields"].orEmpty().contains("ImageTags"))
        assertTrue(query["fields"].orEmpty().contains("BackdropImageTags"))
        assertTrue(query["fields"].orEmpty().contains("UserData"))
    }

    @Test
    fun latestItemsQueryDoesNotRequestTrailers() {
        assertFalse(
            JellyfinApi().latestItemsQuery("user-123")["fields"].orEmpty()
                .contains("RemoteTrailers")
        )
    }

    @Test
    fun nextUpQueryMatchesWebFilteringAndMediaStateOptions() {
        val query = JellyfinApi().nextUpItemsQuery("user-123")

        assertEquals("user-123", query["userId"])
        assertEquals("18", query["limit"])
        assertEquals("0", query["startIndex"])
        assertEquals("true", query["enableImages"])
        assertEquals("true", query["enableUserData"])
        assertEquals("false", query["enableTotalRecordCount"])
        assertEquals("true", query["disableFirstEpisode"])
        assertEquals("false", query["enableResumable"])
        assertEquals("false", query["enableRewatching"])
        assertTrue(query["fields"].orEmpty().contains("UserData"))
    }

    @Test
    fun newlyAddedTvLibraryQueryRequestsEpisodesByDateCreated() {
        val query = JellyfinApi().newlyAddedItemsQuery("tvshows")

        assertEquals("Episode", query["includeItemTypes"])
        assertEquals("DateCreated", query["sortBy"])
        assertEquals("Descending", query["sortOrder"])
    }

    @Test
    fun newlyAddedMovieLibraryQueryStillRequestsMovies() {
        assertEquals("Movie", JellyfinApi().newlyAddedItemsQuery("movies")["includeItemTypes"])
    }

    @Test
    fun detailQueryRequestsUserStatePeopleAndStudios() {
        val query = JellyfinApi().detailItemQuery("user-123")
        assertEquals("user-123", query["userId"])
        assertEquals("true", query["enableUserData"])
        assertTrue(query["fields"].orEmpty().contains("People"))
        assertTrue(query["fields"].orEmpty().contains("Studios"))
        assertTrue(query["fields"].orEmpty().contains("PremiereDate"))
    }

    @Test
    fun searchQueryMatchesWebSearchRequestShape() {
        val query = JellyfinApi().searchQuery("user-123", "  dune  ")

        assertEquals("dune", query["searchTerm"])
        assertEquals("Series,Movie", query["includeItemTypes"])
        assertEquals("40", query["limit"])
        assertEquals("true", query["recursive"])
        assertEquals("true", query["enableImages"])
        assertEquals("true", query["enableUserData"])
        assertTrue(query["fields"].orEmpty().contains("ImageTags"))
        assertTrue(query["fields"].orEmpty().contains("UserData"))
        assertFalse(query.containsKey("sortBy"))
    }

    @Test
    fun libraryQueryUsesCollectionItemTypesPagingAndServerSort() {
        val query = JellyfinApi().libraryItemsQuery(
            userId = "user-123",
            library = Library("shows", "Shows", "tvshows"),
            startIndex = 40,
            limit = 40,
            sort = LibrarySort(LibrarySortBy.DateCreated, SortOrder.Ascending),
        )

        assertEquals("shows", query["parentId"])
        assertEquals("Series", query["includeItemTypes"])
        assertEquals("40", query["startIndex"])
        assertEquals("40", query["limit"])
        assertEquals("DateCreated", query["sortBy"])
        assertEquals("Ascending", query["sortOrder"])
        assertEquals("true", query["enableTotalRecordCount"])
    }

    @Test
    fun libraryQueryRequestsBoxSetsForCollectionLibraries() {
        val query = JellyfinApi().libraryItemsQuery(
            userId = "user-123",
            library = Library("collections", "Collections", "boxsets"),
            startIndex = 0,
            limit = 40,
            sort = LibrarySort(),
        )

        assertEquals("BoxSet", query["includeItemTypes"])
    }
}

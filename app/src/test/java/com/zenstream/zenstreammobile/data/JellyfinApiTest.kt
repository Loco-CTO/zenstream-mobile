package com.zenstream.zenstreammobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}

package com.zenstream.zenstreammobile.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogApiTest {
    @Test
    fun parsesCatalogItemWithCanonicalArtworkAndState() {
        val item = catalogMediaItem(
            JSONObject()
                .put("id", "movie-1")
                .put("libraryId", "movies")
                .put("type", "movie")
                .put("name", "Fallback")
                .put("metadata", JSONObject()
                    .put("title", "Dune")
                    .put("runtimeMinutes", 155)
                    .put("images", JSONObject()
                        .put("Primary", JSONObject().put("url", "/api/catalog/items/movie-1/images/Primary?language=en"))
                        .put("Backdrop", JSONObject().put("url", "/api/catalog/items/movie-1/images/Backdrop?language=en"))))
                .put("userState", JSONObject().put("favorite", true).put("played", false).put("positionSeconds", 42.0))
        )

        assertEquals("movie-1", item.id)
        assertEquals("movies", item.libraryId)
        assertEquals("Dune", item.name)
        assertEquals("Movie", item.type)
        assertTrue(item.favorite)
        assertFalse(item.played)
        assertEquals(42_000_0000L, item.playbackPositionTicks)
        assertEquals(setOf("Primary"), item.imageTags.keys)
        assertEquals(1, item.backdropImageTags.size)
        assertNull(item.imageTags["Thumb"])
    }

    @Test
    fun parsesCatalogResultArray() {
        val root = JSONObject().put("items", JSONArray()
            .put(JSONObject().put("id", "a").put("type", "series").put("metadata", JSONObject().put("title", "A")))
            .put(JSONObject().put("id", "b").put("type", "collection").put("metadata", JSONObject().put("title", "B"))))

        assertEquals(listOf("a", "b"), catalogItems(root).map { it.id })
        assertEquals(listOf("Series", "BoxSet"), catalogItems(root).map { it.type })
    }
}

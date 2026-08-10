package com.zenstream.zenstreammobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogUpdatesManagerTest {
    @Test
    fun parsesScopedCatalogUpdate() {
        val update =
            parseCatalogInvalidations(
                    """{"type":"catalog.updated","libraryId":"library-1","rootEntityId":"series-1","generation":42}"""
                )
                .single()

        assertEquals("library-1", update.libraryId)
        assertEquals("series-1", update.rootEntityId)
        assertEquals(42L, update.generation)
    }

    @Test
    fun parsesReconnectStatusForEveryVisibleLibrary() {
        val updates =
            parseCatalogInvalidations(
                """{"type":"catalog.status","libraries":[{"id":"movies","catalogGeneration":3,"lastRootEntityId":null},{"id":"shows","catalogGeneration":8,"lastRootEntityId":"series-2"}]}"""
            )

        assertEquals(listOf("movies", "shows"), updates.map { it.libraryId })
        assertNull(updates.first().rootEntityId)
        assertEquals(8L, updates.last().generation)
    }

    @Test
    fun scopedInvalidationsOnlyRefreshMatchingLibraryAndRoot() {
        val update = CatalogInvalidation("library-1", "series-1", 5)

        assertTrue(update.affectsLibrary("library-1"))
        assertFalse(update.affectsLibrary("library-2"))
        assertTrue(update.affectsDetail("library-1", "series-1", "episode-1"))
        assertFalse(update.affectsDetail("library-1", "series-2", "episode-2"))
        assertTrue(CatalogInvalidation().affectsDetail("library-2", "series-2", "episode-2"))
    }

    @Test
    fun reconnectBackoffIsBounded() {
        assertEquals(1_000L, catalogReconnectDelayMillis(0))
        assertEquals(30_000L, catalogReconnectDelayMillis(20))
    }
}

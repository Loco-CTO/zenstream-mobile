package com.zenstream.zenstreammobile.offline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineCatalogTest {
    @Test
    fun parsesLocalizedNumberingAndMultiDiscMusic() {
        val row =
            offlineCatalogItem(
                "profile",
                JSONObject()
                    .put("id", "track")
                    .put("type", "track")
                    .put("parentId", "album")
                    .put("discNumber", 2)
                    .put("trackNumber", 3)
                    .put("metadata", JSONObject().put("title", "音乐")),
            )
        assertEquals("音乐", row.name)
        assertEquals(2, row.disc)
        assertEquals(3, row.track)
        assertEquals("album", row.parentId)
    }

    @Test
    fun rejectsNonAdvancingOrOversizedPages() {
        fun payload() =
            JSONObject()
                .put("id", "s")
                .put("state", "ready")
                .put("itemCount", 1000)
                .put("items", JSONArray().put(JSONObject().put("id", "a")))
                .put("nextOffset", 0)
        assertThrows(IllegalArgumentException::class.java) { SelectionPage.parse(payload(), 0) }
        val valid = SelectionPage.parse(payload().put("nextOffset", 1), 0)
        assertEquals(1, valid.next)
        val huge = JSONArray()
        repeat(101) { huge.put(JSONObject().put("id", "$it")) }
        assertThrows(IllegalArgumentException::class.java) {
            SelectionPage.parse(payload().put("items", huge), 0)
        }
    }

    @Test
    fun searchEscapesWildcardsAndPresentationExcludesPlayback() {
        assertEquals("%50!%!_!!%", searchPattern("50%_!"))
        val payload = JSONObject().put("id", "a").put("name", "Movie")
        val first = presentationRevision(payload)
        payload.put("userState", JSONObject().put("played", true))
        assertEquals(first, presentationRevision(payload))
    }
}

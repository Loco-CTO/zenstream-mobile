package com.zenstream.zenstreammobile.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineEpisodeNavigationTest {
    @Test
    fun ordersSpecialsDoubleEpisodesAndSeasonBoundaries() {
        val episodes =
            listOf(
                episode("season-one-double", season = 1, number = 1, end = 2),
                episode("season-two-first", season = 2, number = 1),
                episode("special", season = 0, number = 4),
                episode("season-one-third", season = 1, number = 3),
            )

        assertEquals(
            OfflineEpisodeNeighborIds(previous = "special", next = "season-one-third"),
            offlineEpisodeNeighborIds("season-one-double", episodes),
        )
        assertEquals(
            OfflineEpisodeNeighborIds(previous = "season-one-double", next = "season-two-first"),
            offlineEpisodeNeighborIds("season-one-third", episodes),
        )
        assertEquals(
            OfflineEpisodeNeighborIds(previous = "season-one-third", next = null),
            offlineEpisodeNeighborIds("season-two-first", episodes),
        )
    }

    @Test
    fun bundleNeighborIsAuthoritativeIncludingAnExplicitlyEmptyBoundary() {
        val next =
            org.json.JSONObject().put("previous", org.json.JSONObject.NULL).put("next", "episode-2")
        val end =
            org.json.JSONObject().put("previous", "episode-1").put("next", org.json.JSONObject.NULL)

        assertEquals(BundledEpisodeNeighbor("episode-2"), next.bundledEpisodeNeighbor("next"))
        assertEquals(BundledEpisodeNeighbor(null), end.bundledEpisodeNeighbor("next"))
        assertNull(org.json.JSONObject().bundledEpisodeNeighbor("next"))
    }

    private fun episode(id: String, season: Int, number: Int, end: Int = number) =
        OfflineCatalogItem(
            profile = "profile",
            id = id,
            libraryId = null,
            parentId = "season-$season",
            type = "episode",
            name = id,
            season = season,
            episode = number,
            episodeEnd = end,
            payload = "{}",
        )
}

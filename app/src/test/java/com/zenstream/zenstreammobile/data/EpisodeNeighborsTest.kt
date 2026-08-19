package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeNeighborsTest {
    @Test
    fun resolvesBothNeighborsWithinTheCurrentSeason() {
        val season = season("s1", 1)
        val current = episode("ep2", 1, 2)
        val result =
            resolveEpisodeNeighbors(
                current,
                listOf(season),
            ) {
                listOf(episode("ep1", 1, 1), current, episode("ep3", 1, 3))
            }

        assertEquals("ep1", result.previous?.id)
        assertEquals("ep3", result.next?.id)
    }

    @Test
    fun crossesSeasonBoundariesInBothDirections() {
        val seasons = listOf(season("s1", 1), season("s2", 2), season("s3", 3))
        val current = episode("s2e2", 2, 2)
        val episodes =
            mapOf(
                "s1" to listOf(episode("s1e1", 1, 1), episode("s1e2", 1, 2)),
                "s2" to listOf(episode("s2e1", 2, 1), current),
                "s3" to listOf(episode("s3e1", 3, 1)),
            )

        val result = resolveEpisodeNeighbors(current, seasons) { episodes.getValue(it.id) }

        assertEquals("s2e1", result.previous?.id)
        assertEquals("s3e1", result.next?.id)
    }

    @Test
    fun returnsNoNeighborsForNonEpisodesOrMissingEpisodeIdentity() {
        val season = season("s1", 1)
        assertEquals(
            EpisodeNeighbors(),
            resolveEpisodeNeighbors(MediaItem("movie", "Movie", type = "Movie"), listOf(season)) {
                emptyList()
            },
        )
        assertEquals(
            EpisodeNeighbors(),
            resolveEpisodeNeighbors(
                MediaItem("ep", "Episode", type = "Episode", seriesId = "series"),
                listOf(season),
            ) {
                emptyList()
            },
        )
    }

    @Test
    fun returnsNullAtTheFirstAndLastEpisode() {
        val season = season("s1", 1)
        val first = episode("ep1", 1, 1)
        val last = episode("ep2", 1, 2)
        val firstResult = resolveEpisodeNeighbors(first, listOf(season)) { listOf(first, last) }
        val lastResult = resolveEpisodeNeighbors(last, listOf(season)) { listOf(first, last) }

        assertNull(firstResult.previous)
        assertNull(lastResult.next)
    }

    private fun season(id: String, number: Int) =
        MediaItem(id, "Season $number", type = "Season", indexNumber = number)

    private fun episode(id: String, season: Int, number: Int) =
        MediaItem(
            id = id,
            name = "Episode $number",
            type = "Episode",
            seriesId = "series",
            parentIndexNumber = season,
            indexNumber = number,
        )
}

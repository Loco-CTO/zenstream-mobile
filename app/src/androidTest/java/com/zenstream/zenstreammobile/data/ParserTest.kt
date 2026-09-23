package com.zenstream.zenstreammobile.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ParserTest {
    @Test
    fun parsesHomeMediaItems() {
        val json =
            JSONObject(
                """{"Items":[{"Id":"1","Name":"Example","Type":"Movie","ImageTags":{"Primary":"primary-tag"},"BackdropImageTags":["backdrop-tag"],"UserData":{"PlayedPercentage":25.0}}]}"""
            )
        val item = parseMediaItems(json).single()
        assertEquals("1", item.id)
        assertEquals("Example", item.name)
        assertEquals("primary-tag", item.imageTags["Primary"])
        assertEquals(listOf("backdrop-tag"), item.backdropImageTags)
        assertEquals(25.0, item.playedPercentage!!, 0.0)
    }

    @Test
    fun parsesDetailMetadataAndPeople() {
        val json =
            JSONObject(
                """{"Items":[{"Id":"series-1","Name":"Example Series","Type":"Series","PremiereDate":"2024-01-02T00:00:00Z","Genres":["Drama","Mystery"],"Studios":[{"Name":"Studio"}],"People":[{"Name":"Actor","Role":"Lead","Type":"Actor","PrimaryImageTag":"person-tag"}],"RecursiveItemCount":12,"UserData":{"Played":true,"IsFavorite":true}}]}"""
            )
        val item = parseMediaItems(json).single()
        assertEquals("2024-01-02T00:00:00Z", item.premiereDate)
        assertEquals(listOf("Drama", "Mystery"), item.genres)
        assertEquals(listOf("Studio"), item.studios)
        assertEquals(12, item.recursiveItemCount)
        assertEquals("Actor", item.people.single().name)
        assertEquals("Lead", item.people.single().role)
        assertEquals("person-tag", item.people.single().primaryImageTag)
        assertEquals(true, item.played)
        assertEquals(true, item.favorite)
    }

    @Test
    fun parsesAudioPlaylistSummaryAndOrderedEntries() {
        val playlist =
            parsePlaylist(
                JSONObject(
                    """{"id":"playlist-1","name":"Road Trip","description":"Long drives","isPrivate":false,"shareToken":"share-token","itemCount":2,"isOwner":true,"artworkItems":[{"Id":"track-a","Name":"Track A","Type":"Audio"}],"items":[{"entryId":"entry-a","position":0,"addedAt":"2026-09-01T12:00:00Z","item":{"Id":"track-a","Name":"Track A","Type":"Audio"}},{"entryId":"entry-b","position":1,"addedAt":"2026-09-01T12:01:00Z","item":{"Id":"track-b","Name":"Track B","Type":"Audio"}}]}"""
                )
            )

        assertEquals("playlist-1", playlist.summary.id)
        assertEquals("Road Trip", playlist.summary.name)
        assertEquals(false, playlist.summary.isPrivate)
        assertEquals("share-token", playlist.summary.shareToken)
        assertEquals(2, playlist.summary.itemCount)
        assertEquals(listOf("track-a", "track-b"), playlist.items.map { it.item.id })
        assertEquals(listOf(0, 1), playlist.items.map { it.position })
    }

    @Test
    fun selectsRequestedSeasonThenItemSeasonThenSeasonOne() {
        val seasons =
            listOf(
                com.zenstream.zenstreammobile.model.MediaItem("s2", "Second", indexNumber = 2),
                com.zenstream.zenstreammobile.model.MediaItem("s1", "First", indexNumber = 1),
            )
        val episode =
            com.zenstream.zenstreammobile.model.MediaItem(
                "e",
                "Episode",
                type = "Episode",
                seasonId = "s2",
            )
        assertEquals("s1", selectInitialSeason(episode, seasons, "s1")?.id)
        assertEquals("s2", selectInitialSeason(episode, seasons)?.id)
        assertEquals("s1", selectInitialSeason(episode.copy(seasonId = null), seasons)?.id)
    }
}

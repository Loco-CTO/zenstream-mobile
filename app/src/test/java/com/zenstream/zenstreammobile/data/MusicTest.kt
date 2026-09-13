package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.ArtistCredit
import com.zenstream.zenstreammobile.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicTest {
    @Test
    fun preservesAtomicOrderIdsAndExactJoinPhrases() {
        val credits =
            normalizeArtistCredits(
                listOf(
                    ArtistCredit("artist-a", "Artist A", " & "),
                    ArtistCredit("artist-b", "Artist B", " feat. "),
                ),
                listOf(ArtistCredit("artist-a", "Artist A")),
            )

        assertEquals(listOf("artist-a", "artist-b"), credits.map { it.id })
        assertEquals("Artist A & Artist B feat. ", formatArtistCredits(credits))
    }

    @Test
    fun neverSynthesizesJoinedArtistWhenCreditBoundariesAreKnown() {
        val track =
            MediaItem(
                id = "track-1",
                name = "Track",
                type = "Audio",
                artistId = "artist-a",
                albumArtist = "Artist A",
                artists = listOf("Artist A", "Artist B"),
            )

        assertEquals(listOf("Artist A", "Artist B"), artistCreditsForTrack(track).map { it.name })
        assertEquals("Artist A, Artist B", formatTrackArtists(track))
        assertEquals("artist-a", artistCreditsForTrack(track).first().id)
    }

    @Test
    fun albumCreditsUseThePrimaryArtistIdWithoutChangingOrder() {
        val album =
            MediaItem(
                id = "album-1",
                name = "Album",
                type = "MusicAlbum",
                artistId = "artist-a",
                albumArtist = "Artist A",
                artistCredits =
                    listOf(
                        ArtistCredit(name = "Artist A", joinPhrase = " & "),
                        ArtistCredit(name = "Artist B"),
                    ),
            )

        val credits = artistCreditsForAlbum(album)

        assertEquals(listOf("Artist A", "Artist B"), credits.map { it.name })
        assertEquals("artist-a", credits.first().id)
        assertEquals("Artist A & Artist B", formatArtistCredits(credits))
    }
}

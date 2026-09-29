package com.zenstream.zenstreammobile.audio

import com.zenstream.zenstreammobile.model.AudioPlayerState
import com.zenstream.zenstreammobile.model.AudioQueueEntry
import com.zenstream.zenstreammobile.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSessionQueueProjectionTest {
    @Test
    fun projectsEveryQueueEntryInPlaybackOrderWithStableEntryIdentity() {
        val state =
            AudioPlayerState(
                queue =
                    listOf(
                        AudioQueueEntry(
                            "entry-a",
                            MediaItem(
                                id = "track-1",
                                name = "First track",
                                album = "First album",
                                albumArtist = "Artist",
                                durationSeconds = 90.5,
                            ),
                        ),
                        AudioQueueEntry(
                            "entry-b",
                            MediaItem(
                                id = "track-1",
                                name = "First track",
                                album = "First album",
                                albumArtist = "Artist",
                                durationSeconds = 90.5,
                            ),
                        ),
                    ),
                currentIndex = 1,
            )

        val items = audioSessionQueueItems(state)

        assertEquals(listOf("entry-a", "entry-b"), items.map { it.entryId })
        assertEquals(
            listOf("zenstream:queue:entry-a", "zenstream:queue:entry-b"),
            items.map { it.mediaId },
        )
        assertEquals(listOf("First track", "First track"), items.map { it.title })
        assertEquals(listOf("Artist", "Artist"), items.map { it.artist })
        assertEquals(listOf("First album", "First album"), items.map { it.album })
        assertEquals(listOf(90_500_000L, 90_500_000L), items.map { it.durationUs })
    }
}

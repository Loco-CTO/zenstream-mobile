package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.ArtistCredit
import com.zenstream.zenstreammobile.model.AudioQueueEntry
import com.zenstream.zenstreammobile.model.AudioQueueSnapshot
import com.zenstream.zenstreammobile.model.AudioRepeatMode
import com.zenstream.zenstreammobile.model.MediaItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AudioQueueStorageTest {
    @Test
    fun roundTripsSafeQueueMetadataAndKeepsDuplicateTracksWithUniqueEntries() {
        val track =
            MediaItem(
                id = "track-1",
                name = "Track",
                type = "Audio",
                albumId = "album-1",
                artistId = "artist-1",
                album = "Album",
                albumArtist = "Artist",
                artists = listOf("Artist"),
                artistCredits = listOf(ArtistCredit("artist-1", "Artist", "")),
                durationSeconds = 123.5,
            )
        val snapshot =
            AudioQueueSnapshot(
                serverUrl = "https://music.example/",
                userId = "user-1",
                entries =
                    listOf(
                        AudioQueueEntry("entry-a", track, "instance-a"),
                        AudioQueueEntry("entry-b", track),
                    ),
                currentIndex = 1,
                positionSeconds = 42.0,
                shuffle = true,
                repeatMode = AudioRepeatMode.Queue,
                updatedAt = 123L,
            )

        val encoded = snapshot.toJson().toString()
        val restored = audioQueueSnapshotFromJson(JSONObject(encoded))

        assertEquals("https://music.example", restored?.serverUrl)
        assertEquals(listOf("entry-a", "entry-b"), restored?.entries?.map { it.entryId })
        assertEquals(listOf("track-1", "track-1"), restored?.entries?.map { it.track.id })
        assertEquals("instance-a", restored?.entries?.first()?.playbackInstanceId)
        assertEquals(AudioRepeatMode.Queue, restored?.repeatMode)
        assertFalse(encoded.contains("access"))
        assertFalse(encoded.contains("token"))
        assertFalse(encoded.contains("url"))
    }

    @Test
    fun rejectsObsoleteMalformedAndDuplicateEntrySnapshots() {
        assertNull(audioQueueSnapshotFromJson(JSONObject().put("schemaVersion", 0)))

        val malformed =
            JSONObject()
                .put("schemaVersion", AUDIO_QUEUE_SCHEMA_VERSION)
                .put("serverUrl", "https://music.example")
                .put("userId", "user-1")
                .put(
                    "entries",
                    org.json.JSONArray()
                        .put(
                            JSONObject()
                                .put("entryId", "same")
                                .put("track", JSONObject().put("id", "track-1")),
                        )
                        .put(
                            JSONObject()
                                .put("entryId", "same")
                                .put("track", JSONObject().put("id", "track-2")),
                        ),
                )

        val parsed = audioQueueSnapshotFromJson(malformed)
        assertEquals(listOf("same"), parsed?.entries?.map { it.entryId })
    }
}

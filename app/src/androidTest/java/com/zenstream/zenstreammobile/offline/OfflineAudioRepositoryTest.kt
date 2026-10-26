package com.zenstream.zenstreammobile.offline

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.model.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OfflineAudioRepositoryTest {
    @Test
    fun queueSurvivesLostCredentialsWithStableEntriesAndSeparateServerIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = File(context.cacheDir, "offline-audio-repository-test")
        val catalog =
            OfflineCatalogRepository(db) { _, _, _, _ -> error("Unexpected network request") }
        val audio = OfflineAudioRepository(catalog, OfflineStorage(db, root, scope))
        try {
            val profile =
                OfflineProfile(
                    OfflineProfile.identity("server", "user"),
                    "server",
                    "user",
                    "https://example.invalid",
                    "User",
                )
            db.offline().putProfile(profile)
            val identity = requireNotNull(audio.identity(profile.id, null))
            assertNull(identity.session)
            val snapshot =
                AudioQueueSnapshot(
                    serverUrl = profile.serverUrl,
                    userId = profile.account,
                    entries =
                        listOf(
                            AudioQueueEntry(
                                "entry-one",
                                MediaItem("track", "Song", type = "Audio"),
                                playbackInstanceId = "play-one",
                            )
                        ),
                    positionSeconds = 12.345,
                    shuffle = true,
                    repeatMode = AudioRepeatMode.Queue,
                    durationSeconds = 120.0,
                    offlineOnly = true,
                )
            audio.save(identity, snapshot)
            val restored = requireNotNull(audio.restore(identity))
            assertEquals("entry-one", restored.entries.single().entryId)
            assertEquals("play-one", restored.entries.single().playbackInstanceId)
            assertEquals(12.345, restored.positionSeconds, 0.0001)
            assertTrue(restored.offlineOnly)
            assertTrue(restored.shuffle)
            assertEquals(AudioRepeatMode.Queue, restored.repeatMode)
            db.offline()
                .putSelection(
                    OfflineSelection(
                        profile.id,
                        "artist-selection",
                        "artist",
                        "catalog",
                        "Artist",
                        "{}",
                    )
                )
            db.offline()
                .putItems(
                    listOf(
                        OfflineCatalogItem(
                            profile.id,
                            "a2",
                            null,
                            "album-a",
                            "track",
                            "Second",
                            disc = 2,
                            track = 1,
                            payload = "{}",
                        ),
                        OfflineCatalogItem(
                            profile.id,
                            "b1",
                            null,
                            "album-b",
                            "track",
                            "First",
                            disc = 1,
                            track = 1,
                            payload = "{}",
                        ),
                        OfflineCatalogItem(
                            profile.id,
                            "a1",
                            null,
                            "album-a",
                            "track",
                            "First",
                            disc = 1,
                            track = 1,
                            payload = "{}",
                        ),
                    )
                )
            db.offline()
                .putMembers(
                    listOf("a1", "a2", "b1").mapIndexed { ordinal, id ->
                        OfflineMember(profile.id, "artist-selection", id, ordinal)
                    }
                )
            assertEquals(
                listOf("a1", "a2", "b1"),
                db.offline().musicChildren(profile.id, "artist", 100, 0).map { it.id },
            )
            assertEquals(
                listOf("a1", "a2"),
                db.offline().musicChildren(profile.id, "album-a", 100, 0).map { it.id },
            )
            val recreated =
                profile.copy(
                    id = OfflineProfile.identity("new-server", "user"),
                    serverInstance = "new-server",
                )
            db.offline().putProfile(recreated)
            assertNull(audio.restore(requireNotNull(audio.identity(recreated.id, null))))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { audio.save(identity, snapshot.copy(userId = "other")) }
            }
            db.offline().putProfile(profile.copy(locked = true))
            assertNull(audio.identity(profile.id, null))
        } finally {
            requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
            db.close()
            root.deleteRecursively()
        }
    }
}

package com.zenstream.zenstreammobile.offline

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflinePlaylistRepositoryTest {
    @Test
    fun localCreateEditsOrderAndDeletionStayInOneOrderedOutbox() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            val dao = database.offline()
            dao.putProfile(
                OfflineProfile("p", "server", "account", "https://example.invalid", "Local")
            )
            dao.putItems(listOf(track("track-1"), track("track-2")))
            dao.putAsset(asset("asset-1", "track-1"))
            dao.putAsset(asset("asset-2", "track-2"))
            val catalog =
                OfflineCatalogRepository(database) { _, _, _, _ -> error("No network expected") }
            val writer = OfflinePlaybackStateRepository(context, database)
            val repository = OfflinePlaylistRepository(database, writer, catalog)

            val id = repository.create("p", " Road trip ")
            repository.rename("p", id, "Road again")
            repository.rename("p", id, "Road final")
            repository.updateDescription("p", id, "A local playlist")
            assertEquals(2, repository.addTracks("p", id, listOf("track-1", "track-1", "track-2")))
            val addOperations = dao.operations("p", 100).filter { it.kind == "playlist_add" }
            assertEquals(
                addOperations.associate {
                    it.payload.let(::JSONObject).getString("trackId") to it.id
                },
                repository.entries("p", id, limit = 10).associate { it.track to it.entryId },
            )

            assertTrue(repository.moveTrack("p", id, "track-2", 0))
            assertEquals(
                listOf("track-2", "track-1"),
                repository.entries("p", id, 10).map { it.track },
            )
            assertTrue(repository.removeTrack("p", id, "track-1"))
            assertEquals(listOf("track-2"), repository.entries("p", id, 10).map { it.track })
            assertTrue(repository.delete("p", id))

            val operations = dao.operations("p", 100)
            assertEquals(
                listOf(
                    "playlist_create",
                    "playlist_name",
                    "playlist_name",
                    "playlist_description",
                    "playlist_add",
                    "playlist_add",
                    "playlist_order",
                    "playlist_remove",
                    "playlist_delete",
                ),
                operations.map { it.kind },
            )
            assertEquals(1L, operations[1].baseRevision)
            assertEquals(2L, operations[2].baseRevision)
            assertEquals(1L, operations[3].baseRevision)
            assertEquals(listOf(id), operations.map { it.target }.distinct())
            assertTrue(dao.playlist("p", id)!!.deleted)
            assertTrue(
                "playlist deletion must retain the downloaded files",
                dao.playable("p", "track-1").isNotEmpty(),
            )
            assertTrue(dao.playable("p", "track-2").isNotEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun incompleteMembershipIsReadOnlyAndMetadataRefreshKeepsPendingScalarOverlay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            val dao = database.offline()
            dao.putProfile(
                OfflineProfile("p", "server", "account", "https://example.invalid", "Local")
            )
            dao.putItems(listOf(track("old-track"), track("remote-track")))
            dao.putPlaylist(
                OfflinePlaylist(
                    profile = "p",
                    id = "partial",
                    metadata =
                        JSONObject()
                            .put("name", "Partial")
                            .put("stateRevisions", JSONObject())
                            .toString(),
                    expectedCount = 2,
                    cursor = 100,
                )
            )
            dao.putPlaylistEntries(
                listOf(OfflinePlaylistEntry("p", "partial", "old-track", "entry-old", 0))
            )
            dao.putPlaylist(
                OfflinePlaylist(
                    profile = "p",
                    id = "remote",
                    metadata =
                        JSONObject()
                            .put("name", "Local pending name")
                            .put("stateRevisions", JSONObject())
                            .toString(),
                    complete = true,
                    expectedCount = 1,
                )
            )
            dao.putPlaylistEntries(
                listOf(OfflinePlaylistEntry("p", "remote", "old-track", "entry-old", 0))
            )
            val catalog =
                OfflineCatalogRepository(database) { _, _, _, _ ->
                    JSONObject()
                        .put("serverInstanceId", "server")
                        .put(
                            "objects",
                            org.json
                                .JSONArray()
                                .put(
                                    JSONObject()
                                        .put("id", "remote")
                                        .put("status", "updated")
                                        .put("presentationRevision", "new-revision")
                                        .put(
                                            "stateRevisions",
                                            JSONObject()
                                                .put("name", JSONObject().put("revision", 1)),
                                        )
                                        .put(
                                            "playlist",
                                            JSONObject()
                                                .put("id", "remote")
                                                .put("name", "Server name")
                                                .put("description", JSONObject.NULL)
                                                .put("isPrivate", true)
                                                .put("itemCount", 1)
                                                .put(
                                                    "items",
                                                    org.json
                                                        .JSONArray()
                                                        .put(
                                                            JSONObject()
                                                                .put("entryId", "server-entry")
                                                                .put(
                                                                    "item",
                                                                    JSONObject()
                                                                        .put("id", "remote-track")
                                                                        .put("type", "track")
                                                                        .put(
                                                                            "name",
                                                                            "Remote track",
                                                                        ),
                                                                )
                                                        ),
                                                ),
                                        )
                                        .put("offset", 0)
                                        .put("nextOffset", JSONObject.NULL)
                                ),
                        )
                }
            val writer = OfflinePlaybackStateRepository(context, database)
            val repository = OfflinePlaylistRepository(database, writer, catalog)
            database.withTransaction {
                writer.appendAccountOperation(
                    "p",
                    "remote",
                    "playlist_name",
                    0,
                    JSONObject().put("value", "Local pending name"),
                )
            }

            val failure =
                runCatching { repository.rename("p", "partial", "Blocked") }.exceptionOrNull()
            assertNotNull("incomplete membership must block edits", failure)
            assertTrue(
                catalog.cachePlaylistPage(
                    com.zenstream.zenstreammobile.model.AuthSession(
                        "https://example.invalid",
                        "token",
                        "account",
                        "Local",
                    ),
                    "p",
                    "remote",
                    "en",
                )
            )
            assertEquals(
                listOf("remote-track"),
                repository.entries("p", "remote", 10).map { it.track },
            )
            assertEquals(
                "Local pending name",
                JSONObject(repository.playlist("p", "remote")!!.metadata).getString("name"),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun albumAndArtistAddsUseOnlyKnownTracksAndCapEachPartialGroupAtOneHundred() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            val dao = database.offline()
            dao.putProfile(
                OfflineProfile("p", "server", "account", "https://example.invalid", "Local")
            )
            val artist = group("artist", "artist", "Artist")
            val release = group("album", "release", "Album")
            val artistTracks =
                (0 until 101).map { track("artist-track-%03d".format(it), artistId = "artist") }
            val albumOnly = track("album-only", albumId = "album")
            dao.putItems(listOf(artist, release) + artistTracks + albumOnly)
            val catalog =
                OfflineCatalogRepository(database) { _, _, _, _ -> error("No network expected") }
            val repository =
                OfflinePlaylistRepository(
                    database,
                    OfflinePlaybackStateRepository(context, database),
                    catalog,
                )
            val id = repository.create("p", "Locally known set")

            assertEquals(100, repository.addKnownTracksFromGroup("p", id, "artist"))
            assertEquals(1, repository.addKnownTracksFromGroup("p", id, "album"))
            val entries = repository.entries("p", id, limit = 10_000)
            assertEquals(101, entries.size)
            assertFalse(entries.any { it.track == "artist-track-100" })
            assertTrue(entries.any { it.track == "album-only" })
            assertEquals(
                listOf("album", "artist"),
                repository.knownMusicGroups("p", limit = 10).map { it.id }.sorted(),
            )
            assertEquals(101, dao.operations("p", 200).count { it.kind == "playlist_add" })
        } finally {
            database.close()
        }
    }

    private fun group(id: String, type: String, name: String) =
        OfflineCatalogItem(
            profile = "p",
            id = id,
            libraryId = "library",
            parentId = null,
            type = type,
            name = name,
            payload = JSONObject().put("id", id).put("type", type).put("name", name).toString(),
        )

    private fun track(id: String, artistId: String? = null, albumId: String? = null) =
        OfflineCatalogItem(
            profile = "p",
            id = id,
            libraryId = "library",
            parentId = "release",
            type = "track",
            name = id,
            payload =
                JSONObject()
                    .put("id", id)
                    .put("type", "track")
                    .put("name", id)
                    .apply {
                        artistId?.let { put("artistId", it) }
                        albumId?.let { put("albumId", it) }
                    }
                    .toString(),
        )

    private fun asset(id: String, item: String) =
        OfflineAsset(
            profile = "p",
            id = id,
            item = item,
            quality = "original",
            sourceId = "source-$id",
            sourceRevision = "revision-$id",
            size = 4,
            sha256 = "hash-$id",
            mimeType = "audio/flac",
            descriptor = "{}",
            relativePath = "media/$id.flac",
            state = "complete",
            verifiedAt = 1,
        )
}

package com.zenstream.zenstreammobile.offline

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflinePlaybackStateRepositoryTest {
    @Test
    fun checkpointsCoalesceAndExplicitActionsKeepOrderedSequences() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            database.offline().putProfile(profile())
            database.offline().putItems(listOf(catalogItem("movie", "movie", null)))
            database.offline().putAsset(asset("movie-asset", "movie"))
            val repository = OfflinePlaybackStateRepository(context, database)

            repository.checkpoint("p", "movie", 12.0, 100.0, historyEnabled = true)
            val first = database.offline().operations("p", 100).single()
            repository.checkpoint("p", "movie", 29.0, 100.0, historyEnabled = true)
            val coalesced = database.offline().operations("p", 100).single()
            assertEquals(first.sequence, coalesced.sequence)
            assertEquals(first.id, coalesced.id)
            assertEquals(29.0, JSONObject(coalesced.payload).getDouble("positionSeconds"), 0.0)
            assertEquals(2L, database.offline().profile("p")?.nextSequence)
            assertEquals(listOf("movie"), repository.continueWatching("p").map { it.id })

            repository.complete("p", "movie", 100.0, historyEnabled = true)
            assertTrue(repository.setPlayed("p", "movie", false))
            val operations = database.offline().operations("p", 100)
            assertEquals(listOf("checkpoint", "complete", "unwatched"), operations.map { it.kind })
            assertEquals(listOf(1L, 2L, 3L), operations.map { it.sequence })
            val state = database.offline().playbackState("p", "movie")
            assertEquals(false, state?.played)
            assertEquals(0.0, state?.positionSeconds ?: -1.0, 0.0)
            assertTrue(repository.continueWatching("p").isEmpty())
            assertTrue(repository.setPlayed("p", "movie", true))
            assertFalse(repository.setPlayed("p", "movie", true))
            assertEquals(4L, database.offline().operations("p", 100).last().sequence)
        } finally {
            database.close()
        }
    }

    @Test
    fun historyDisabledResumeStaysLocalAndOutboxSurvivesDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "offline-playback-state-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, OfflineDatabase::class.java, name).build()
        var database = open()
        try {
            database.offline().putProfile(profile())
            database.offline().putItems(listOf(catalogItem("movie", "movie", null)))
            val repository = OfflinePlaybackStateRepository(context, database)
            repository.checkpoint("p", "movie", 36.0, 100.0, historyEnabled = false)

            assertTrue(database.offline().operations("p", 100).isEmpty())
            assertEquals(
                36.0,
                JSONObject(database.offline().state("p", "movie")!!.localOnlyResume!!)
                    .getDouble("positionSeconds"),
                0.0,
            )
            assertFalse(database.offline().profile("p")!!.historyEnabled)

            database
                .offline()
                .putOperation(
                    OfflineOperation("p", 7, "op-7", "movie", "watched", 1, false, 0, 0, 0, "{}")
                )
            database.close()
            database = open()
            assertEquals("op-7", database.offline().operations("p", 100).single().id)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun bulkWatchedOnlyTouchesDownloadedEpisodesAndNextUpNeverSkipsMissingNeighbor() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            database.offline().putProfile(profile())
            database
                .offline()
                .putItems(
                    listOf(
                        catalogItem("series", "series", null),
                        catalogItem("season", "season", "series"),
                        catalogItem("episode-1", "episode", "season", season = 1, episode = 1),
                        catalogItem("episode-2", "episode", "season", season = 1, episode = 2),
                        catalogItem("episode-3", "episode", "season", season = 1, episode = 3),
                    )
                )
            database.offline().putAsset(asset("a1", "episode-1"))
            database.offline().putAsset(asset("a1-alt", "episode-1"))
            database.offline().putAsset(asset("a2", "episode-2"))
            val repository = OfflinePlaybackStateRepository(context, database)
            assertEquals(
                OfflineWatchedCounts(2, 0),
                repository.downloadedEpisodeWatchCounts("p", "series"),
            )

            assertEquals(2, repository.markDownloadedEpisodes("p", "series", true))
            assertEquals(
                listOf("episode-1", "episode-2"),
                database.offline().operations("p", 100).map { it.target },
            )
            assertNull(database.offline().playbackState("p", "episode-3"))

            database.offline().putAsset(asset("a3", "episode-3"))
            repository.saveEpisodeNeighbors("p", "episode-2", "episode-1", "episode-3")
            assertEquals(
                "episode-3",
                database.offline().playbackState("p", "episode-2")?.nextEpisodeId,
            )
            assertEquals("series", database.offline().episodeSeriesId("p", "episode-2"))
            assertEquals(1, database.offline().playable("p", "episode-3").size)
            assertEquals(
                listOf("episode-2", "episode-1"),
                database.offline().completedDownloadedEpisodes("p", 500).map { it.target },
            )
            assertEquals(
                "episode-3",
                database.offline().completedDownloadedEpisodes("p", 500).first().nextEpisodeId,
            )
            assertNotNull(database.offline().item("p", "episode-3"))
            assertEquals(listOf("episode-3"), repository.nextUp("p").map { it.id })
            repository.saveEpisodeNeighbors("p", "episode-2", "episode-1", "missing-episode")
            assertTrue(repository.nextUp("p").isEmpty())
        } finally {
            database.close()
        }
    }

    @Test
    fun musicPlayIdentityIsIdempotentAndDistinctStartsAccumulate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            database.offline().putProfile(profile())
            database.offline().putItems(listOf(catalogItem("track", "track", null)))
            val repository = OfflinePlaybackStateRepository(context, database)
            val firstId = "10cc2af5-8df6-4af0-b9c7-2b05c6fbd33d"
            val secondId = "73c28f43-ae3d-47c2-98a5-267afcc7385d"
            assertTrue(repository.musicPlay("p", "track", firstId, historyEnabled = true))
            assertFalse(repository.musicPlay("p", "track", firstId, historyEnabled = true))
            assertTrue(repository.musicPlay("p", "track", secondId, historyEnabled = true))
            assertEquals(
                listOf(firstId, secondId),
                database.offline().operations("p", 100).map { it.id },
            )
            assertEquals(
                2,
                JSONObject(database.offline().state("p", "track")!!.overlay).getInt("playCount"),
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun audioCheckpointPersistsResumeAndRespectsHistoryPrivacy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            database.offline().putProfile(profile())
            database.offline().putItems(listOf(catalogItem("track", "track", null)))
            val repository = OfflinePlaybackStateRepository(context, database)

            repository.checkpoint("p", "track", 43.5, 180.0, historyEnabled = true)
            assertEquals(
                43.5,
                database.offline().playbackState("p", "track")!!.positionSeconds,
                0.0,
            )
            assertEquals("checkpoint", database.offline().operations("p", 100).single().kind)

            repository.checkpoint("p", "track", 61.0, 180.0, historyEnabled = false)
            assertEquals(
                61.0,
                database.offline().playbackState("p", "track")!!.positionSeconds,
                0.0,
            )
            assertEquals(1, database.offline().operations("p", 100).size)
            assertEquals(
                61.0,
                JSONObject(database.offline().state("p", "track")!!.localOnlyResume!!)
                    .getDouble("positionSeconds"),
                0.0,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun favoriteAndFollowOperationsStayOrderedAndOverlaysSurviveCatalogRefresh() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            database.offline().putProfile(profile())
            database
                .offline()
                .putItems(
                    listOf(
                        catalogItem("movie", "movie", null),
                        catalogItem("series", "series", null),
                        catalogItem("artist", "artist", null),
                        catalogItem("episode", "episode", "series"),
                        catalogItem("track", "track", null),
                    )
                )
            database
                .offline()
                .putState(
                    OfflineUserState(
                        "p",
                        "movie",
                        canonical =
                            JSONObject().put("favorite", false).put("following", false).toString(),
                        revisions = JSONObject().put("favorite", 7).put("following", 2).toString(),
                    )
                )
            val repository = OfflinePlaybackStateRepository(context, database)

            assertTrue(repository.setFavorite("p", "movie", true))
            assertTrue(repository.setFavorite("p", "movie", false))
            assertTrue(repository.setFavorite("p", "movie", true))
            assertTrue(repository.setFollowing("p", "movie", true))
            assertTrue(repository.setFollowing("p", "episode", true))
            assertFalse(repository.setFollowing("p", "series", true))
            assertTrue(repository.setFollowing("p", "artist", true))
            assertUnsupportedFollow { repository.setFollowing("p", "track", true) }

            val operations = database.offline().operations("p", 100)
            assertEquals(
                listOf(
                    "favorite",
                    "unfavorite",
                    "favorite",
                    "follow",
                    "follow",
                    "follow",
                ),
                operations.map { it.kind },
            )
            assertEquals((1L..6L).toList(), operations.map { it.sequence })
            assertEquals(7L, operations.first().baseRevision)
            assertEquals(2L, operations[3].baseRevision)
            assertEquals(listOf("movie", "series", "artist"), operations.drop(3).map { it.target })
            assertTrue(repository.hasPendingFlags("p", "movie"))
            assertTrue(repository.hasPendingFlags("p", "episode"))

            val catalog = OfflineCatalogRepository(database) { _, _, _, _ -> JSONObject() }
            catalog.ingestItems(
                "p",
                listOf(
                    stateSnapshot("movie", "movie", favorite = false, following = false),
                    stateSnapshot("series", "series", following = false),
                    stateSnapshot("artist", "artist", following = false),
                    stateSnapshot("episode", "episode", parentId = "series", following = false),
                ),
            )
            assertTrue(catalog.item("p", "movie")!!.favorite)
            assertEquals(true, catalog.item("p", "movie")!!.following)
            assertEquals(true, catalog.item("p", "series")!!.following)
            assertEquals(true, catalog.item("p", "artist")!!.following)
            assertEquals("series", database.offline().episodeSeriesId("p", "episode"))
            assertTrue(
                JSONObject(database.offline().state("p", "series")!!.overlay)
                    .getBoolean("following")
            )
            val episode = catalog.item("p", "episode")
            assertEquals("Episode", episode?.type)
            assertEquals(true, episode?.following)
        } finally {
            database.close()
        }
    }

    @Test
    fun accountFlagOverlaysAndOutboxRemainProfileScoped() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            database.offline().putProfile(profile())
            database.offline().putProfile(profile("other", "account-2"))
            database
                .offline()
                .putItems(
                    listOf(
                        catalogItem("movie", "movie", null),
                        catalogItem("movie", "movie", null, profile = "other"),
                    )
                )
            val repository = OfflinePlaybackStateRepository(context, database)

            assertTrue(repository.setFavorite("p", "movie", true))
            assertTrue(database.offline().operations("other", 100).isEmpty())
            val catalog = OfflineCatalogRepository(database) { _, _, _, _ -> JSONObject() }
            assertTrue(catalog.item("p", "movie")!!.favorite)
            assertFalse(catalog.item("other", "movie")!!.favorite)

            database.offline().putProfile(profile("locked", "account-3").copy(locked = true))
            database
                .offline()
                .putItems(listOf(catalogItem("movie", "movie", null, profile = "locked")))
            assertLockedProfile { repository.setFavorite("locked", "movie", true) }
        } finally {
            database.close()
        }
    }

    private suspend fun assertUnsupportedFollow(action: suspend () -> Unit) {
        var rejected = false
        try {
            action()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("unsupported entity must not enqueue a Follow operation", rejected)
    }

    private suspend fun assertLockedProfile(action: suspend () -> Unit) {
        var rejected = false
        try {
            action()
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue("locked profile must not mutate account state", rejected)
    }

    private fun profile(id: String = "p", account: String = "account") =
        OfflineProfile(id, "server-instance", account, "https://example.invalid", "Local")

    private fun catalogItem(
        id: String,
        type: String,
        parentId: String?,
        season: Int = 0,
        episode: Int = 0,
        profile: String = "p",
    ) =
        OfflineCatalogItem(
            profile = profile,
            id = id,
            libraryId = "library",
            parentId = parentId,
            type = type,
            name = id,
            season = season,
            episode = episode,
            episodeEnd = episode,
            payload =
                JSONObject()
                    .put("id", id)
                    .put("type", type)
                    .put("name", id)
                    .apply { parentId?.let { put("parentId", it) } }
                    .toString(),
        )

    private fun stateSnapshot(
        id: String,
        type: String,
        favorite: Boolean = false,
        following: Boolean = false,
        parentId: String? = null,
    ) =
        JSONObject()
            .put("id", id)
            .put("type", type)
            .put("name", id)
            .put("userState", JSONObject().put("favorite", favorite).put("following", following))
            .apply { parentId?.let { put("parentId", it) } }

    private fun asset(id: String, item: String) =
        OfflineAsset(
            profile = "p",
            id = id,
            item = item,
            quality = "original",
            sourceId = "source-$id",
            sourceRevision = "revision-$id",
            size = 1,
            sha256 = "digest-$id",
            mimeType = "video/mp4",
            descriptor = "{}",
            relativePath = "media/$id.mp4",
            state = "complete",
            verifiedAt = 1,
        )
}

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

    private fun profile() =
        OfflineProfile("p", "server-instance", "account", "https://example.invalid", "Local")

    private fun catalogItem(
        id: String,
        type: String,
        parentId: String?,
        season: Int = 0,
        episode: Int = 0,
    ) =
        OfflineCatalogItem(
            profile = "p",
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

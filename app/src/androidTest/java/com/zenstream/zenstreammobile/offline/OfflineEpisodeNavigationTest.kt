package com.zenstream.zenstreammobile.offline

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineEpisodeNavigationTest {
    private fun database() =
        Room.inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                OfflineDatabase::class.java,
            )
            .build()

    @Test
    fun unavailableActualNextEpisodeIsNotSkippedForALaterDownload() = runBlocking {
        val db = database()
        val dao = db.offline()
        try {
            seedSeries(
                dao,
                listOf(
                    episode("episode-1", 1, 1),
                    episode("episode-2", 1, 2),
                    episode("episode-3", 1, 3),
                ),
            )
            dao.putAsset(asset("episode-1"))
            dao.putAsset(asset("episode-3"))
            val catalog = catalog(db)
            val bundle =
                JSONObject()
                    .put(
                        "episodeNeighbors",
                        JSONObject().put("previous", JSONObject.NULL).put("next", "episode-2"),
                    )

            val missing = catalog.episodeNavigation("profile", "episode-1", bundle)
            assertNull(missing.previous)
            assertNull(missing.next)
            assertTrue(missing.nextUnavailable)

            dao.putAsset(asset("episode-2"))
            val available = catalog.episodeNavigation("profile", "episode-1", bundle)
            assertEquals("episode-2", available.next?.id)
            assertFalse(available.nextUnavailable)
        } finally {
            db.close()
        }
    }

    @Test
    fun hierarchyFallbackUsesSpecialsDoubleEpisodeOrderAndDownloadedNeighborsOnly() = runBlocking {
        val db = database()
        val dao = db.offline()
        try {
            seedSeries(
                dao,
                listOf(
                    episode("special", 0, 1),
                    episode("season-one-double", 1, 1, 2),
                    episode("season-one-third", 1, 3),
                    episode("season-two-first", 2, 1),
                ),
            )
            listOf("special", "season-one-double", "season-one-third", "season-two-first").forEach {
                dao.putAsset(asset(it))
            }
            val catalog = catalog(db)

            val first = catalog.episodeNavigation("profile", "season-one-double", JSONObject())
            assertEquals("special", first.previous?.id)
            assertEquals("season-one-third", first.next?.id)
            assertFalse(first.nextUnavailable)

            val boundary = catalog.episodeNavigation("profile", "season-one-third", JSONObject())
            assertEquals("season-two-first", boundary.next?.id)

            val final =
                catalog.episodeNavigation(
                    "profile",
                    "season-two-first",
                    JSONObject()
                        .put(
                            "episodeNeighbors",
                            JSONObject()
                                .put("previous", "season-one-third")
                                .put("next", JSONObject.NULL),
                        ),
                )
            assertNull(final.next)
            assertFalse(final.nextUnavailable)
        } finally {
            db.close()
        }
    }

    private fun catalog(db: OfflineDatabase) =
        OfflineCatalogRepository(db) { _, _, _, _ ->
            error("Offline navigation must not make requests")
        }

    private suspend fun seedSeries(dao: OfflineDao, episodes: List<OfflineCatalogItem>) {
        dao.putProfile(
            OfflineProfile(
                id = "profile",
                serverInstance = "server",
                account = "account",
                serverUrl = "https://example.invalid",
                displayName = "Account",
            )
        )
        val seasons =
            episodes
                .map { it.season }
                .distinct()
                .map { number ->
                    catalogItem(
                        id = "season-$number",
                        type = "season",
                        parentId = "series",
                        season = number,
                    )
                }
        dao.putItems(listOf(catalogItem("series", "series")) + seasons + episodes)
    }

    private fun episode(id: String, season: Int, number: Int, end: Int = number) =
        catalogItem(
            id = id,
            type = "episode",
            parentId = "season-$season",
            season = season,
            episode = number,
            episodeEnd = end,
        )

    private fun catalogItem(
        id: String,
        type: String,
        parentId: String? = null,
        season: Int = 0,
        episode: Int = 0,
        episodeEnd: Int = episode,
    ): OfflineCatalogItem {
        val payload =
            JSONObject()
                .put("id", id)
                .put("type", type)
                .put("name", id)
                .put("metadata", JSONObject().put("title", id))
        parentId?.let { payload.put("parentId", it) }
        if (type == "episode" || type == "season") payload.put("seasonNumber", season)
        if (type == "episode")
            payload
                .put("seriesId", "series")
                .put("episodeNumber", episode)
                .put("episodeEndNumber", episodeEnd)
        return offlineCatalogItem("profile", payload)
    }

    private fun asset(item: String) =
        OfflineAsset(
            profile = "profile",
            id = "asset-$item",
            item = item,
            quality = "original",
            sourceId = "source-$item",
            sourceRevision = "revision",
            size = 16,
            sha256 = "digest",
            mimeType = "video/mp4",
            descriptor = "{}",
            relativePath = "profile/$item.mp4",
            state = "complete",
            verifiedAt = 1,
        )
}

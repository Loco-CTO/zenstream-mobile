package com.zenstream.zenstreammobile.offline

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.model.AuthSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineDownloadManagementTest {
    @Test
    fun downloadNavigationGroupsTitlesAndOnlyOpensSavedChildren() = runBlocking {
        val db = database()
        val dao = db.offline()
        try {
            fun row(
                id: String,
                type: String,
                parent: String? = null,
                season: Int = 0,
                episode: Int = 0,
            ) =
                OfflineCatalogItem(
                    "p",
                    id,
                    null,
                    parent,
                    type,
                    id,
                    season = season,
                    episode = episode,
                    payload = "{}",
                )
            dao.putItems(
                listOf(
                    row("series", "series"),
                    row("season", "season", "series"),
                    row("episode-2", "episode", "season", 1, 2),
                    row("episode-1", "episode", "season", 1, 1),
                    row("episode-cached", "episode", "season", 1, 3),
                    row("album", "release", "artist"),
                    row("artist", "artist"),
                    row("track", "track", "album"),
                    row("standalone-track", "track"),
                    row("standalone-episode", "episode"),
                    row("movie", "movie"),
                    row("locked-movie", "movie"),
                )
            )
            val asset =
                OfflineAsset(
                    "p",
                    "a",
                    "movie",
                    "original",
                    "source",
                    "r",
                    1,
                    "hash",
                    "video/mp4",
                    "{}",
                    "path",
                    state = "complete",
                )
            listOf(
                    "episode-2",
                    "episode-1",
                    "track",
                    "standalone-track",
                    "standalone-episode",
                    "movie",
                )
                .forEach { id -> dao.putAsset(asset.copy(id = "asset-$id", item = id)) }
            dao.putAsset(asset.copy(id = "duplicate-quality", item = "episode-1"))
            dao.putAsset(asset.copy(id = "locked", item = "locked-movie", locked = true))
            dao.putAsset(asset.copy(profile = "other", id = "other", item = "locked-movie"))
            assertEquals(
                setOf("series", "album", "standalone-track", "standalone-episode", "movie"),
                dao.downloadRoots("p", "", 100, 0).map { it.id }.toSet(),
            )
            assertEquals(
                listOf("episode-1", "episode-2"),
                dao.downloadChildren("p", "series", "series", "%", 100, 0).map { it.id },
            )
            assertEquals(
                listOf("track"),
                dao.downloadChildren("p", "album", "release", "%", 100, 0).map { it.id },
            )
            assertEquals(
                listOf("series", "standalone-episode"),
                dao.downloadRoots("p", "tv", 100, 0).map { it.id },
            )
            assertTrue(
                dao.downloadRoots("p", "", 2, 0)
                    .map { it.id }
                    .intersect(dao.downloadRoots("p", "", 2, 2).map { it.id }.toSet())
                    .isEmpty()
            )
            // Credited artist membership may point at tracks rather than catalog parents.
            dao.putItems(listOf(row("credited", "artist")))
            dao.putSelection(
                OfflineSelection(
                    "p",
                    "credited-selection",
                    "credited",
                    "artist",
                    "Credited",
                    "{}",
                    state = "complete",
                )
            )
            dao.putMembers(listOf(OfflineMember("p", "credited-selection", "track", 0)))
            assertEquals(
                listOf("album"),
                dao.downloadChildren("p", "credited", "artist", "%", 100, 0).map { it.id },
            )
            assertEquals(0, dao.pendingDownloadCount("p").first())
            dao.putSelection(OfflineSelection("p", "building", "series", "series", "Series", "{}"))
            assertEquals(1, dao.pendingDownloadCount("p").first())
            dao.putTransfer(OfflineTransfer("p", "queued", "building", "episode-cached", "{}"))
            assertEquals(1, dao.pendingDownloadCount("p").first())
            dao.putSelection(
                OfflineSelection(
                    "p",
                    "finished",
                    "movie",
                    "movie",
                    "A completed film",
                    "{}",
                    state = "complete",
                )
            )
            assertEquals("building", dao.managedSelectionPage("p", 1, 0).first().single().id)
            assertEquals(
                listOf("queued"),
                dao.selectionTransferPage("p", "building", 20, 0).first().map { it.id },
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun managedDownloadsHideSelectionsBeingRemoved() = runBlocking {
        val db = database()
        val dao = db.offline()
        try {
            dao.putSelection(
                OfflineSelection(
                    "p",
                    "kept",
                    "movie",
                    "movie",
                    "Saved movie",
                    "{}",
                    state = "complete",
                )
            )
            dao.putSelection(
                OfflineSelection(
                    "p",
                    "removing",
                    "movie",
                    "movie",
                    "Removing movie",
                    "{}",
                    state = "removal_pending",
                )
            )

            assertEquals(
                listOf("kept"),
                dao.managedSelectionPage("p", 20, 0).first().map { it.id },
            )
        } finally {
            db.close()
        }
    }

    private fun database() =
        Room.inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                OfflineDatabase::class.java,
            )
            .build()

    @Test
    fun savedBrowsingIncludesParentsAndSelectionRootsWithoutLeakingRemovedOrOtherProfiles() =
        runBlocking {
            val db = database()
            val dao = db.offline()
            try {
                fun row(id: String, type: String, parent: String? = null) =
                    OfflineCatalogItem("p", id, null, parent, type, id, payload = "{}")
                dao.putItems(
                    listOf(
                        row("episode", "episode", "season"),
                        row("season", "season", "series"),
                        row("series", "series"),
                        row("collection", "collection"),
                        row("cached-only", "movie"),
                    )
                )
                val asset =
                    OfflineAsset(
                        "p",
                        "asset",
                        "episode",
                        "original",
                        "source",
                        "revision",
                        4,
                        "hash",
                        "video/mp4",
                        "{}",
                        "path",
                        state = "complete",
                    )
                dao.putAsset(asset)
                dao.putAsset(asset.copy(profile = "other", item = "cached-only"))
                dao.putSelection(
                    OfflineSelection("p", "selection", "collection", "catalog", "Collection", "{}")
                )
                dao.putReference(OfflineAssetReference("p", "selection", "asset"))
                assertEquals(
                    setOf("episode", "season", "series", "collection"),
                    dao.browse("p", "", "%", 100, 0).map { it.id }.toSet(),
                )
                assertEquals(3, dao.browse("p", "tv", "%", 100, 0).size)
                dao.putAsset(asset.copy(state = "pending_delete"))
                assertTrue(dao.browse("p", "", "%", 100, 0).isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun transferPagingAndMetadataQueueRemainBoundedAndResumable() = runBlocking {
        val db = database()
        val dao = db.offline()
        try {
            repeat(205) { index ->
                dao.putTransfer(
                    OfflineTransfer(
                        "p",
                        "t$index",
                        "selection",
                        "item$index",
                        "{}",
                        priority = index,
                    )
                )
            }
            val first = dao.transferPage("p", 100, 0).first()
            val second = dao.transferPage("p", 100, 100).first()
            assertEquals(100, first.size)
            assertEquals(100, second.size)
            assertTrue(
                first.map { it.id }.toSet().intersect(second.map { it.id }.toSet()).isEmpty()
            )
            assertEquals(5, dao.transferPage("p", 100, 200).first().size)
            dao.putSelection(
                OfflineSelection(
                    "p",
                    "metadata",
                    "playlist",
                    "playlist_metadata",
                    "List",
                    "{}",
                    state = "metadata_only",
                )
            )
            assertEquals(1, dao.playlistMetadataWork("p").size)
            dao.pauseEnumeration("p")
            assertTrue(dao.playlistMetadataWork("p").isEmpty())
            dao.resumeEnumeration("p")
            assertEquals("metadata_only", dao.selection("p", "metadata")?.state)
            dao.putSelection(
                requireNotNull(dao.selection("p", "metadata")).copy(state = "metadata_complete")
            )
            assertTrue(dao.playlistMetadataWork("p").isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun responseArrivingAfterPauseCannotResumeEnumeration() = runBlocking {
        val db = database()
        val dao = db.offline()
        try {
            dao.putProfile(OfflineProfile("p", "server", "user", "https://example.invalid", "User"))
            dao.putSelection(OfflineSelection("p", "s", "movie", "catalog", "Movie", "{}"))
            val catalog =
                OfflineCatalogRepository(db) { _, _, _, _ ->
                    dao.pauseEnumeration("p")
                    JSONObject()
                        .put("id", "s")
                        .put("state", "ready")
                        .put("itemCount", 1)
                        .put(
                            "items",
                            JSONArray().put(JSONObject().put("id", "movie").put("type", "movie")),
                        )
                }
            val result =
                catalog.ingestSelectionPage(
                    AuthSession("https://example.invalid", "token", "user", "User"),
                    "p",
                    "s",
                )
            assertEquals("paused_enumerating", result.state)
            assertTrue(dao.members("p", "s", 100, 0).isEmpty())
            assertNull(dao.item("p", "movie"))
        } finally {
            db.close()
        }
    }
}

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

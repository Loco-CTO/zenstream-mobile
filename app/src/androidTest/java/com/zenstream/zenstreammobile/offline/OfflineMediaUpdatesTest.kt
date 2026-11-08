package com.zenstream.zenstreammobile.offline

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.model.AuthSession
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineMediaUpdatesTest {
    @Test
    fun replacementPreparationResumesWithoutCreatingAnotherSelection() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            val source = JSONObject().put("id", "source").put("revision", "new-revision")
            val preview = MediaUpdatePreview(fixture.oldAsset(), listOf(source), listOf("original"))
            val updates = OfflineMediaUpdates(fixture.catalog) { _, _, _, _ -> error("unexpected") }
            assertTrue(updates.enqueue(fixture.session, preview, source, "original", false))
            var creations = 0
            var deletions = 0
            var ready = false
            val bytes = "new bytes".toByteArray()
            val digest =
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    "%02x".format(it)
                }
            val request: OfflineRequest = { _, path, method, _ ->
                when {
                    path == "/api/offline/capabilities" ->
                        JSONObject().put("serverInstanceId", "instance")
                    path == "/api/offline/selections" && method == "POST" -> {
                        creations++
                        JSONObject().put("id", "server-update").put("state", "enumerating")
                    }
                    path == "/api/offline/selections/server-update" && method == "GET" ->
                        JSONObject().put("state", if (ready) "ready" else "enumerating")
                    path == "/api/offline/selections/server-update" && method == "DELETE" -> {
                        deletions++
                        if (deletions == 1)
                            throw java.io.IOException("cleanup interrupted after publication")
                        JSONObject()
                    }
                    path == "/api/offline/assets" ->
                        JSONObject()
                            .put("id", fixture.newId)
                            .put("state", "ready")
                            .put("profile", "original")
                            .put("sourceId", "source")
                            .put("sourceRevision", "new-revision")
                            .put("sizeBytes", bytes.size)
                            .put("sha256", digest)
                            .put("mimeType", "video/mp4")
                            .put("contentUrl", "/api/offline/assets/new/content")
                            .put("bundleUrl", "/api/offline/assets/new/bundle")
                    path.startsWith("/api/offline/assets/new/bundle") ->
                        JSONObject()
                            .put("items", JSONArray())
                            .put("artwork", JSONArray())
                            .put("subtitles", JSONArray())
                    else -> error("Unexpected request: $method $path")
                }
            }
            fun coordinator() =
                DownloadCoordinator(
                    fixture.catalog,
                    fixture.storage,
                    request,
                    binary = { _, _, _, receive ->
                        receive(
                            Response.Builder()
                                .request(
                                    Request.Builder().url("https://example.invalid/content").build()
                                )
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .header("ETag", "\"$digest\"")
                                .body(bytes.toResponseBody())
                                .build()
                        )
                    },
                    activeSession = { fixture.session },
                )
            coordinator().runOnce(fixture.session, fixture.profile, updatesOnly = true)
            val transfer = fixture.dao.mediaUpdateTransfers(fixture.profile).single()
            assertEquals(
                "server-update",
                JSONObject(transfer.options).getString("updateSelectionId"),
            )
            ready = true
            fixture.dao.putTransfer(transfer.copy(nextAttemptMs = 0))
            coordinator().runOnce(fixture.session, fixture.profile, updatesOnly = true)
            assertEquals(
                "complete",
                fixture.dao.mediaUpdateTransfers(fixture.profile).single().state,
            )
            coordinator().runOnce(fixture.session, fixture.profile, updatesOnly = true)
            assertEquals(1, creations)
            assertEquals(2, deletions)
            assertTrue(fixture.dao.mediaUpdateTransfers(fixture.profile).isEmpty())
            assertEquals(
                fixture.newId,
                fixture.dao.latestCompleteAsset(fixture.profile, "movie")?.id,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun disabledAutomaticUpdatesPauseBeforeAnyPreparationOrTransfer() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            val source = JSONObject().put("id", "source").put("revision", "new-revision")
            val preview = MediaUpdatePreview(fixture.oldAsset(), listOf(source), listOf("original"))
            OfflineMediaUpdates(fixture.catalog) { _, _, _, _ -> error("unexpected") }
                .enqueue(fixture.session, preview, source, "original", true)
            val coordinator =
                DownloadCoordinator(
                    fixture.catalog,
                    fixture.storage,
                    request = { _, path, _, _ ->
                        check(path == "/api/offline/capabilities")
                        JSONObject().put("serverInstanceId", "instance")
                    },
                    binary = { _, _, _, _ -> error("disabled automatic update must not download") },
                    activeSession = { fixture.session },
                    automaticUpdatesAllowed = { false },
                )
            coordinator.runOnce(fixture.session, fixture.profile, updatesOnly = true)
            assertEquals("paused", fixture.dao.mediaUpdateTransfers(fixture.profile).single().state)
            assertEquals(
                fixture.oldId,
                fixture.dao.latestCompleteAsset(fixture.profile, "movie")?.id,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun replacementMovesSharedReferencesKeepsProgressAndProtectsActivePlayback() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            val lease = fixture.storage.acquire(fixture.profile, fixture.oldId)
            val state = OfflinePlaybackState(fixture.profile, "movie", 45.0, 120.0, false, 10)
            fixture.dao.putPlaybackState(state)
            fixture.prepareReplacement()
            fixture.storage.publish(fixture.newAsset(), "replacement")
            assertEquals(
                listOf("album", "selection"),
                fixture.dao.assetSelections(fixture.profile, fixture.newId),
            )
            assertEquals(0, fixture.dao.references(fixture.profile, fixture.oldId))
            assertEquals(state, fixture.dao.playbackState(fixture.profile, "movie"))
            assertTrue(lease.file.isFile)
            assertEquals("old bytes", lease.file.readText())
            assertEquals(
                fixture.newId,
                fixture.dao.latestCompleteAsset(fixture.profile, "movie")?.id,
            )
            assertEquals(
                "new bytes",
                fixture.storage.paths
                    .resolve(fixture.storage.paths.relative(fixture.profile, fixture.newId))
                    .readText(),
            )
            lease.close()
            withTimeout(5_000) {
                while (fixture.dao.asset(fixture.profile, fixture.oldId) != null) delay(10)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancelledOrCorruptReplacementLeavesWorkingDownloadAndReferencesIntact() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            fixture.prepareReplacement()
            val transfer = fixture.dao.transfer(fixture.profile, "replacement")!!
            fixture.dao.putTransfer(transfer.copy(state = "cancelled"))
            assertTrue(
                runCatching { fixture.storage.publish(fixture.newAsset(), "replacement") }.isFailure
            )
            fixture.dao.putTransfer(transfer)
            fixture.storage.staging(fixture.profile, fixture.newId).writeText("corrupt")
            assertTrue(
                runCatching { fixture.storage.publish(fixture.newAsset(), "replacement") }.isFailure
            )
            assertEquals("complete", fixture.dao.asset(fixture.profile, fixture.oldId)?.state)
            assertEquals(2, fixture.dao.references(fixture.profile, fixture.oldId))
            assertEquals(
                "old bytes",
                fixture.storage.paths
                    .resolve(fixture.storage.paths.relative(fixture.profile, fixture.oldId))
                    .readText(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun duplicateRequestsAreCoalescedAndAutomaticUpdatesCannotChangeSourceOrQuality() =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.seed()
                val source = JSONObject().put("id", "source").put("revision", "new-revision")
                val other = JSONObject().put("id", "other").put("revision", "other-revision")
                val preview =
                    MediaUpdatePreview(
                        fixture.oldAsset(),
                        listOf(source, other),
                        listOf("original", "video-720p"),
                    )
                val updates =
                    OfflineMediaUpdates(fixture.catalog) { _, _, _, _ ->
                        error("enqueue must be durable without network work")
                    }
                val unchangedSource =
                    JSONObject().put("id", "source").put("revision", "old-revision")
                assertFalse(
                    updates.enqueue(
                        fixture.session,
                        MediaUpdatePreview(
                            fixture.oldAsset(),
                            listOf(unchangedSource),
                            listOf("original"),
                        ),
                        unchangedSource,
                        "original",
                        false,
                    )
                )
                assertTrue(updates.enqueue(fixture.session, preview, source, "original", false))
                assertFalse(updates.enqueue(fixture.session, preview, source, "original", false))
                assertEquals(1, fixture.dao.mediaUpdateTransfers(fixture.profile).size)
                assertTrue(
                    runCatching {
                            updates.enqueue(fixture.session, preview, other, "original", true)
                        }
                        .isFailure
                )
                assertTrue(
                    runCatching {
                            updates.enqueue(fixture.session, preview, source, "video-720p", true)
                        }
                        .isFailure
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun deletingTheOriginalPreventsAQueuedReplacementFromRepublishingIt() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed()
            fixture.prepareReplacement()
            fixture.storage.removeDownloadedItem(fixture.profile, "movie")
            assertTrue(
                runCatching { fixture.storage.publish(fixture.newAsset(), "replacement") }.isFailure
            )
            assertNull(fixture.dao.latestCompleteAsset(fixture.profile, "movie"))
            assertEquals(0, fixture.dao.references(fixture.profile, fixture.newId))
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "media-updates-${UUID.randomUUID()}")
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        val dao = database.offline()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val storage =
            OfflineStorage(database, root, scope, freeBytes = { 8 * OFFLINE_FREE_RESERVE })
        val profile = "a".repeat(64)
        val oldId = "b".repeat(64)
        val newId = "c".repeat(64)
        val session = AuthSession("https://example.invalid", "token", "account", "Local")
        val catalog =
            OfflineCatalogRepository(database) { _, _, _, _ -> error("unexpected network request") }

        suspend fun seed() {
            dao.putProfile(
                OfflineProfile(profile, "instance", "account", session.serverUrl, "Local")
            )
            dao.putItems(
                listOf(
                    offlineCatalogItem(
                        profile,
                        JSONObject().put("id", "movie").put("type", "movie").put("name", "Movie"),
                    )
                )
            )
            for (selection in listOf("selection", "album")) {
                dao.putSelection(
                    OfflineSelection(
                        profile,
                        selection,
                        "movie",
                        "catalog",
                        "Movie",
                        "{}",
                        state = "complete",
                    )
                )
                dao.putMembers(listOf(OfflineMember(profile, selection, "movie", 0)))
                dao.putReference(OfflineAssetReference(profile, selection, oldId))
            }
            val file = storage.paths.resolve(storage.paths.relative(profile, oldId))
            file.parentFile!!.mkdirs()
            file.writeText("old bytes")
            dao.putAsset(oldAsset())
        }

        suspend fun oldAsset(): OfflineAsset {
            val file = storage.paths.resolve(storage.paths.relative(profile, oldId))
            return OfflineAsset(
                profile,
                oldId,
                "movie",
                "original",
                "source",
                "old-revision",
                file.length(),
                sha256(file),
                "video/mp4",
                "{}",
                storage.paths.relative(profile, oldId),
                state = "complete",
            )
        }

        suspend fun prepareReplacement() {
            dao.putTransfer(
                OfflineTransfer(
                    profile,
                    "replacement",
                    "selection",
                    "movie",
                    JSONObject().put("replacesAssetId", oldId).toString(),
                    state = "verifying",
                    assetId = newId,
                )
            )
            storage.staging(profile, newId).apply {
                parentFile!!.mkdirs()
                writeText("new bytes")
            }
        }

        suspend fun newAsset(): OfflineAsset {
            val file = storage.staging(profile, newId)
            return OfflineAsset(
                profile,
                newId,
                "movie",
                "original",
                "source",
                "new-revision",
                9,
                sha256(file),
                "video/mp4",
                "{}",
                "",
            )
        }

        suspend fun close() {
            scope.coroutineContext[Job]!!.cancelAndJoin()
            database.close()
            root.deleteRecursively()
        }
    }
}

package com.zenstream.zenstreammobile.offline

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.model.AuthSession
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineMetadataRefreshCoordinatorTest {
    @Test
    fun refreshUpdatesPresentationAndCanonicalStateWithoutReplacingMediaOrPendingOverlay() =
        runBlocking {
            val fixture = Fixture()
            try {
                fixture.seed(
                    title = "Old title",
                    bundle = bundle(presentation = "presentation-1", supplement = "supplement-1"),
                )
                val oldPlayback =
                    OfflinePlaybackState(fixture.profileId, "movie", 44.0, 120.0, true, 10)
                fixture.dao.putPlaybackState(oldPlayback)
                val overlay = JSONObject().put("favorite", true).toString()
                val state =
                    OfflineUserState(
                        profile = fixture.profileId,
                        target = "movie",
                        canonical = JSONObject().put("favorite", false).toString(),
                        overlay = overlay,
                        revisions =
                            JSONObject()
                                .put("favorite", JSONObject().put("revision", 3))
                                .toString(),
                        localOnlyResume = JSONObject().put("positionSeconds", 88).toString(),
                    )
                fixture.dao.putState(state)
                fixture.dao.putOperation(
                    OfflineOperation(
                        profile = fixture.profileId,
                        sequence = 1,
                        id = "favorite-operation",
                        target = "movie",
                        kind = "favorite",
                        activityMs = 10,
                        clockTrusted = true,
                        baseRevision = 3,
                        historyEpoch = 0,
                        privacyEpoch = 0,
                        payload = JSONObject().put("value", true).toString(),
                    )
                )

                fixture.refresh(
                    reconcileResult(
                        status = "updated",
                        sourceStatus = "changed",
                        presentationRevision = "presentation-2",
                        canonical = JSONObject().put("favorite", false),
                        stateRevisions =
                            JSONObject().put("favorite", JSONObject().put("revision", 4)),
                        item = item("movie", "New title"),
                    ),
                    bundle(
                        presentation = "presentation-2",
                        supplement = "supplement-1",
                        title = "New title",
                    ),
                )

                val updatedItem = fixture.dao.item(fixture.profileId, "movie")!!
                assertEquals("New title", updatedItem.name)
                assertEquals("presentation-2", updatedItem.presentationRevision)
                assertTrue(updatedItem.newSourceAvailable)
                assertEquals(
                    1,
                    fixture.catalog.availability(fixture.profileId, "movie").newSourceAvailable,
                )
                val updatedState = fixture.dao.state(fixture.profileId, "movie")!!
                assertEquals(overlay, updatedState.overlay)
                assertEquals(state.localOnlyResume, updatedState.localOnlyResume)
                assertEquals(
                    4,
                    JSONObject(updatedState.revisions).getJSONObject("favorite").getInt("revision"),
                )
                assertEquals(1, fixture.dao.targetOperations(fixture.profileId, "movie").size)
                assertEquals(oldPlayback, fixture.dao.playbackState(fixture.profileId, "movie"))
                assertEquals(
                    fixture.mediaPath,
                    fixture.dao.asset(fixture.profileId, fixture.assetId)!!.relativePath,
                )
                assertArrayEquals(
                    fixture.mediaBytes,
                    fixture.storage.paths.resolve(fixture.mediaPath).readBytes(),
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun refreshAtomicallyReplacesSupplementFilesAndBundleMetadata() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed(
                title = "Old title",
                bundle = bundle(presentation = "presentation-1", supplement = "supplement-1"),
            )
            val oldBytes = "old artwork".toByteArray()
            val oldPath = fixture.storage.paths.relative(fixture.profileId, sha256(oldBytes), "art")
            val oldFile = fixture.storage.paths.resolve(oldPath)
            oldFile.parentFile!!.mkdirs()
            oldFile.writeBytes(oldBytes)
            fixture.dao.putSupplements(
                listOf(
                    OfflineSupplement(
                        fixture.profileId,
                        fixture.assetId,
                        "art:movie:Primary",
                        oldPath,
                        "old-art",
                        JSONObject().put("revision", "old-art").toString(),
                    )
                )
            )
            val artwork = "new artwork bytes".toByteArray()
            val subtitle = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n".toByteArray()
            val updatedBundle =
                bundle(
                    presentation = "presentation-2",
                    supplement = "supplement-2",
                    title = "Refreshed title",
                    artworkBytes = artwork,
                    subtitleBytes = subtitle,
                )
            fixture.refresh(
                reconcileResult(status = "unchanged", sourceStatus = "unchanged"),
                updatedBundle,
                binaryResponses = mapOf("artwork" to artwork, "subtitle" to subtitle),
            )

            val asset = fixture.dao.asset(fixture.profileId, fixture.assetId)!!
            assertEquals(fixture.mediaPath, asset.relativePath)
            assertArrayEquals(
                fixture.mediaBytes,
                fixture.storage.paths.resolve(asset.relativePath).readBytes(),
            )
            assertFalse("obsolete unshared artwork should be removed", oldFile.exists())
            val rows = fixture.dao.supplements(fixture.profileId, fixture.assetId)
            assertEquals(setOf("art:movie:Primary", "subtitle:0"), rows.map { it.key }.toSet())
            rows.forEach { assertTrue(fixture.storage.paths.resolve(it.relativePath).isFile) }
            val savedBundle = JSONObject(asset.descriptor).getJSONObject("bundle")
            assertEquals("presentation-2", savedBundle.getString("presentationRevision"))
            assertEquals("supplement-2", savedBundle.getString("supplementRevision"))
            assertEquals("Updated lyrics", savedBundle.getJSONObject("lyrics").getString("text"))
            val updatedItem = fixture.dao.item(fixture.profileId, "movie")!!
            assertEquals("Refreshed title", updatedItem.name)
            assertEquals("presentation-2", updatedItem.presentationRevision)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun failedSupplementFetchDoesNotPublishBundleOrReplaceExistingReferences() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed(
                title = "Old title",
                bundle = bundle(presentation = "presentation-1", supplement = "supplement-1"),
            )
            val previous =
                OfflineSupplement(
                    fixture.profileId,
                    fixture.assetId,
                    "subtitle:0",
                    fixture.storage.paths.relative(
                        fixture.profileId,
                        sha256("old subtitle".toByteArray()),
                        "vtt",
                    ),
                    "old-subtitle",
                    "{}",
                )
            fixture.dao.putSupplements(listOf(previous))
            val oldDescriptor = fixture.dao.asset(fixture.profileId, fixture.assetId)!!.descriptor
            val updatedBundle =
                bundle(
                    presentation = "presentation-2",
                    supplement = "supplement-2",
                    artworkBytes = "artwork".toByteArray(),
                    subtitleBytes = "subtitle".toByteArray(),
                )
            var binaryCalls = 0

            val failure =
                runCatching {
                        fixture.refresh(
                            reconcileResult(status = "unchanged", sourceStatus = "unchanged"),
                            updatedBundle,
                            binaryResponses =
                                mapOf(
                                    "artwork" to "artwork".toByteArray(),
                                    "subtitle" to "subtitle".toByteArray(),
                                ),
                            failBinaryCall = { ++binaryCalls == 2 },
                        )
                    }
                    .exceptionOrNull()

            assertNotNull(failure)
            assertEquals(2, binaryCalls)
            assertEquals(
                oldDescriptor,
                fixture.dao.asset(fixture.profileId, fixture.assetId)!!.descriptor,
            )
            assertEquals(
                listOf(previous),
                fixture.dao.supplements(fixture.profileId, fixture.assetId),
            )
            assertEquals("Old title", fixture.dao.item(fixture.profileId, "movie")!!.name)
            assertEquals(
                fixture.mediaPath,
                fixture.dao.asset(fixture.profileId, fixture.assetId)!!.relativePath,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun revokedCatalogGrantLocksSavedFilesAndCancelsItsRemoteTransfer() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seed(
                title = "Saved title",
                bundle = bundle(presentation = "presentation-1", supplement = "supplement-1"),
            )
            fixture.dao.putTransfer(
                OfflineTransfer(
                    profile = fixture.profileId,
                    id = sha256("transfer".toByteArray()),
                    selection = sha256("selection".toByteArray()),
                    item = "movie",
                    options = "{}",
                    state = "transferring",
                    assetId = fixture.assetId,
                )
            )

            fixture.refresh(
                reconcileResult(status = "unavailable", sourceStatus = "unavailable"),
                bundle(presentation = "presentation-1", supplement = "supplement-1"),
            )

            assertTrue(fixture.dao.item(fixture.profileId, "movie")!!.unavailable)
            assertTrue(fixture.dao.asset(fixture.profileId, fixture.assetId)!!.locked)
            assertEquals(
                "cancelled",
                fixture.dao.profileTransferPage(fixture.profileId, 10, 0).single().state,
            )
            assertTrue(fixture.storage.paths.resolve(fixture.mediaPath).isFile)
        } finally {
            fixture.close()
        }
    }

    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val database =
            Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        val dao = database.offline()
        val profileId = OfflineProfile.identity("server-instance", "account")
        private val root = File(context.cacheDir, "offline-metadata-${UUID.randomUUID()}")
        val storage =
            OfflineStorage(database, root, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        private val session = AuthSession("https://example.invalid", "token", "account", "Local")
        val assetId = sha256("offline-media-asset".toByteArray())
        val mediaBytes = "verified media bytes".toByteArray()
        val mediaPath = storage.paths.relative(profileId, assetId)
        val catalog =
            OfflineCatalogRepository(database) { _, path, _, body ->
                when (path) {
                    "/api/offline/reconcile" -> {
                        assertEquals("server-instance", body!!.getString("serverInstanceId"))
                        assertEquals(
                            "source-1",
                            body.getJSONArray("objects").getJSONObject(0).getString("sourceId"),
                        )
                        reconcileResponse
                    }
                    else -> error("Unexpected catalog request: $path")
                }
            }
        private var reconcileResponse = JSONObject()
        private var currentBundle = JSONObject()
        private var binaryResponses = emptyMap<String, ByteArray>()
        private var failBinaryCall: (Int) -> Boolean = { false }
        private var binaryCalls = 0
        private lateinit var requestHandler: suspend (String, JSONObject?) -> JSONObject

        suspend fun seed(title: String, bundle: JSONObject) {
            dao.putProfile(
                OfflineProfile(
                    profileId,
                    "server-instance",
                    "account",
                    "https://example.invalid",
                    "Local",
                )
            )
            dao.putItems(listOf(item("movie", title).let { offlineCatalogItem(profileId, it) }))
            val mediaFile = storage.paths.resolve(mediaPath)
            mediaFile.parentFile!!.mkdirs()
            mediaFile.writeBytes(mediaBytes)
            dao.putAsset(
                OfflineAsset(
                    profile = profileId,
                    id = assetId,
                    item = "movie",
                    quality = "original",
                    sourceId = "source-1",
                    sourceRevision = "source-revision-1",
                    size = mediaBytes.size.toLong(),
                    sha256 = sha256(mediaBytes),
                    mimeType = "video/mp4",
                    descriptor =
                        JSONObject()
                            .put("downloadOptions", JSONObject().put("language", "en"))
                            .put("bundle", bundle)
                            .toString(),
                    relativePath = mediaPath,
                    state = "complete",
                )
            )
            currentBundle = bundle
        }

        suspend fun refresh(
            reconcile: JSONObject,
            bundle: JSONObject,
            binaryResponses: Map<String, ByteArray> = emptyMap(),
            failBinaryCall: (Int) -> Boolean = { false },
        ) {
            reconcileResponse =
                JSONObject()
                    .put("serverInstanceId", "server-instance")
                    .put("objects", JSONArray().put(reconcile))
            currentBundle = bundle
            this.binaryResponses = binaryResponses
            this.failBinaryCall = failBinaryCall
            binaryCalls = 0
            requestHandler = { path, _ ->
                when {
                    path == "/api/offline/reconcile" -> reconcileResponse
                    path.startsWith("/api/offline/assets/") && path.contains("/bundle?") ->
                        currentBundle
                    else -> error("Unexpected refresh request: $path")
                }
            }
            OfflineMetadataRefreshCoordinator(
                    catalog = catalog,
                    storage = storage,
                    request = { _, path, _, body -> requestHandler(path, body) },
                    binary = { _, url, _, receive ->
                        binaryCalls++
                        if (failBinaryCall(binaryCalls))
                            throw IOException("interrupted metadata fetch")
                        val key =
                            when {
                                url.contains("artwork") -> "artwork"
                                url.contains("subtitle") -> "subtitle"
                                else -> "first"
                            }
                        val bytes = binaryResponses[key] ?: error("Missing response for $url")
                        receive(response(bytes))
                    },
                    loadCursor = { 0 },
                    saveCursor = { _, _ -> },
                )
                .refresh(profileId, session, "en")
        }

        override fun close() {
            database.close()
            root.deleteRecursively()
        }
    }

    companion object {
        private fun item(id: String, name: String) =
            JSONObject()
                .put("id", id)
                .put("type", "movie")
                .put("libraryId", "library")
                .put("name", name)

        private fun reconcileResult(
            status: String,
            sourceStatus: String,
            presentationRevision: String? = null,
            canonical: JSONObject? = null,
            stateRevisions: JSONObject? = null,
            item: JSONObject? = null,
        ) =
            JSONObject()
                .put("id", "movie")
                .put("status", status)
                .put("sourceStatus", sourceStatus)
                .apply {
                    presentationRevision?.let { put("presentationRevision", it) }
                    canonical?.let { put("state", it) }
                    stateRevisions?.let { put("stateRevisions", it) }
                    item?.let { put("item", it) }
                }

        private fun bundle(
            presentation: String,
            supplement: String,
            title: String = "Old title",
            artworkBytes: ByteArray? = null,
            subtitleBytes: ByteArray? = null,
        ): JSONObject {
            val artwork =
                artworkBytes?.let {
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("itemId", "movie")
                                .put("type", "Primary")
                                .put("url", "https://example.invalid/artwork")
                                .put("sha256", sha256(it))
                                .put("revision", "art-$supplement")
                        )
                } ?: JSONArray()
            val subtitles =
                subtitleBytes?.let {
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("index", 0)
                                .put("url", "https://example.invalid/subtitle")
                                .put("sha256", sha256(it))
                                .put("revision", "subtitle-$supplement")
                        )
                } ?: JSONArray()
            return JSONObject()
                .put("itemId", "movie")
                .put("presentationRevision", presentation)
                .put("supplementRevision", supplement)
                .put("items", JSONArray().put(item("movie", title)))
                .put("stateRevisions", JSONObject())
                .put("artwork", artwork)
                .put("subtitles", subtitles)
                .put("lyrics", JSONObject().put("text", "Updated lyrics"))
        }

        private fun response(bytes: ByteArray) =
            Response.Builder()
                .request(Request.Builder().url("https://example.invalid/file").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
                .build()

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}

package com.zenstream.zenstreammobile.offline

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OfflineStorageIntegrationTest {
    @Test
    fun deletingEpisodeRemovesSharedCopiesButKeepsOtherEpisodes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "offline-delete-item-test").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val profile = "a".repeat(64)
        val storage = OfflineStorage(db, root, scope, freeBytes = { 8 * OFFLINE_FREE_RESERVE })
        val dao = db.offline()
        try {
            dao.putProfile(
                OfflineProfile(profile, "server", "user", "https://example.invalid", "User")
            )
            dao.putItems(
                listOf(
                    OfflineCatalogItem(
                        profile,
                        "series",
                        null,
                        null,
                        "series",
                        "Series",
                        payload = "{}",
                    ),
                    OfflineCatalogItem(
                        profile,
                        "one",
                        null,
                        "series",
                        "episode",
                        "One",
                        payload = "{}",
                    ),
                    OfflineCatalogItem(
                        profile,
                        "two",
                        null,
                        "series",
                        "episode",
                        "Two",
                        payload = "{}",
                    ),
                )
            )
            for ((item, id) in listOf("one" to "b".repeat(64), "two" to "c".repeat(64))) {
                val relative = storage.paths.relative(profile, id)
                val file = storage.paths.resolve(relative)
                file.parentFile!!.mkdirs()
                file.writeText("data")
                dao.putAsset(
                    OfflineAsset(
                        profile,
                        id,
                        item,
                        "original",
                        "source",
                        "revision",
                        4,
                        sha256(file),
                        "video/mp4",
                        "{}",
                        relative,
                        state = "complete",
                    )
                )
                dao.putReference(OfflineAssetReference(profile, "series-selection", id))
                dao.putTransfer(
                    OfflineTransfer(
                        profile,
                        "transfer-$item",
                        "series-selection",
                        item,
                        "{}",
                        state = "complete",
                        assetId = id,
                    )
                )
            }
            val firstId = "b".repeat(64)
            dao.putReference(OfflineAssetReference(profile, "other-selection", firstId))
            storage.removeDownloadedItem(profile, "one")
            assertNull(dao.asset(profile, firstId))
            assertEquals("cancelled", dao.transfer(profile, "transfer-one")?.state)
            assertEquals(0, dao.references(profile, firstId))
            assertNotNull(dao.asset(profile, "c".repeat(64)))
            storage.removeDownloadedItem(profile, "series")
            assertNull(dao.asset(profile, "c".repeat(64)))
        } finally {
            requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
            db.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun identityPausedTransferCannotPublishOrAdoptACompletedAsset() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "offline-identity-pause-test").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val profile = "e".repeat(64)
        val id = "f".repeat(64)
        val storage = OfflineStorage(db, root, scope, freeBytes = { 8 * OFFLINE_FREE_RESERVE })
        try {
            db.offline()
                .putProfile(
                    OfflineProfile(profile, "server", "user", "https://example.invalid", "User")
                )
            val transfer =
                OfflineTransfer(
                    profile,
                    "transfer",
                    "selection",
                    "movie",
                    "{}",
                    state = "identity_paused",
                    assetId = id,
                    bytes = 4,
                    total = 4,
                )
            db.offline().putTransfer(transfer)
            val part = storage.staging(profile, id)
            part.parentFile!!.mkdirs()
            part.writeText("data")
            val digest = sha256(part)
            val asset =
                OfflineAsset(
                    profile,
                    id,
                    "movie",
                    "original",
                    "source",
                    "revision",
                    4,
                    digest,
                    "video/mp4",
                    "{}",
                    "",
                )
            val publishError = runCatching { storage.publish(asset, transfer.id) }.exceptionOrNull()
            assertTrue(publishError is IllegalStateException)
            assertFalse(storage.paths.resolve(storage.paths.relative(profile, id)).exists())

            val adoptId = "1".repeat(64)
            val adoptedBytes = "data"
            val adoptedDigest = sha256(File(root, "adopt-source").apply { writeText(adoptedBytes) })
            val adoptedRelative = storage.paths.relative(profile, adoptId)
            val adoptedFile = storage.paths.resolve(adoptedRelative)
            adoptedFile.parentFile!!.mkdirs()
            adoptedFile.writeText(adoptedBytes)
            db.offline()
                .putAsset(
                    asset.copy(
                        id = adoptId,
                        sha256 = adoptedDigest,
                        relativePath = adoptedRelative,
                        state = "complete",
                    )
                )
            val adoptError =
                runCatching { storage.adoptCompleted(profile, adoptId, transfer.id, adoptedDigest) }
                    .exceptionOrNull()
            assertTrue(adoptError is IllegalStateException)
            assertEquals("identity_paused", db.offline().transfer(profile, transfer.id)?.state)
        } finally {
            requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
            db.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun sharedReferencesAndActivePlaybackProtectFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "offline-storage-test")
        root.mkdirs()
        val db = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val profile = "a".repeat(64)
        val id = "b".repeat(64)
        val storage = OfflineStorage(db, root, scope, freeBytes = { 8 * OFFLINE_FREE_RESERVE })
        try {
            db.offline()
                .putProfile(
                    OfflineProfile(profile, "server", "user", "https://example.invalid", "User")
                )
            db.offline()
                .putTransfer(
                    OfflineTransfer(
                        profile,
                        "transfer",
                        "first",
                        "movie",
                        "{}",
                        state = "publishing",
                        assetId = id,
                        bytes = 4,
                        total = 4,
                    )
                )
            val part = storage.staging(profile, id)
            part.parentFile!!.mkdirs()
            part.writeText("data")
            val digest = sha256(part)
            val asset =
                storage.publish(
                    OfflineAsset(
                        profile,
                        id,
                        "movie",
                        "original",
                        "source",
                        "revision",
                        4,
                        digest,
                        "video/mp4",
                        "{}",
                        "",
                    ),
                    "transfer",
                )
            val target = storage.paths.resolve(asset.relativePath)
            assertTrue(target.isFile)
            db.offline().putReference(OfflineAssetReference(profile, "second", id))
            storage.removeSelection(profile, "first")
            assertTrue(target.isFile)
            val lease = storage.acquire(profile, id)
            storage.removeSelection(profile, "second")
            assertEquals("pending_delete", db.offline().asset(profile, id)?.state)
            assertTrue(lease.file.isFile)
            lease.close()
            withTimeout(5000) {
                while (target.exists() || db.offline().asset(profile, id) != null) delay(25)
            }
            assertNull(db.offline().asset(profile, id))
        } finally {
            requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
            db.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun publicationJournalRepairsRenameCrashAndDetectsMissingFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "offline-journal-test").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val profile = "c".repeat(64)
        val id = "d".repeat(64)
        val storage = OfflineStorage(db, root, scope, freeBytes = { 8 * OFFLINE_FREE_RESERVE })
        try {
            val relative = storage.paths.relative(profile, id)
            val final = storage.paths.resolve(relative)
            final.parentFile!!.mkdirs()
            final.writeText("data")
            db.offline()
                .putAsset(
                    OfflineAsset(
                        profile,
                        id,
                        "movie",
                        "original",
                        "source",
                        "revision",
                        4,
                        sha256(final),
                        "video/mp4",
                        "{}",
                        relative,
                    )
                )
            storage.repairPage(profile)
            assertEquals("complete", db.offline().asset(profile, id)?.state)
            final.delete()
            storage.repairPage(profile)
            assertEquals("corrupt", db.offline().asset(profile, id)?.state)
        } finally {
            requireNotNull(scope.coroutineContext[Job]).cancelAndJoin()
            db.close()
            root.deleteRecursively()
        }
    }
}

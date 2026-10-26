package com.zenstream.zenstreammobile.offline

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OfflineStorageIntegrationTest {
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
            scope.cancel()
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
            scope.cancel()
            db.close()
            root.deleteRecursively()
        }
    }
}

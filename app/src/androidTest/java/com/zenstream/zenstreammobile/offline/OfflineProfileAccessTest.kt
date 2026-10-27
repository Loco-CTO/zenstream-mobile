package com.zenstream.zenstreammobile.offline

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.model.AuthSession
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineProfileAccessTest {
    @Test
    fun successfulSameIdentityDiscoveryUnlocksDownloadsButCannotUndoRemoval() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.dao.putProfile(fixture.profile(fixture.profileId, locked = true))
            val repository =
                OfflineCatalogRepository(fixture.database) { _, path, _, _ ->
                    assertEquals("/api/offline/capabilities", path)
                    JSONObject()
                        .put("enabled", true)
                        .put("contractVersion", "offline-v1")
                        .put("serverInstanceId", "server-instance")
                }

            val discovered = repository.discover(fixture.session).first
            assertFalse(discovered.locked)
            assertFalse(fixture.dao.profile(fixture.profileId)!!.locked)

            fixture.dao.putProfile(discovered.copy(locked = true, deletePending = true))
            val failure = runCatching { repository.discover(fixture.session) }.exceptionOrNull()
            assertNotNull("an explicit remove choice must not be undone by later sign-in", failure)
            assertTrue(fixture.dao.profile(fixture.profileId)!!.locked)
            assertTrue(fixture.dao.profile(fixture.profileId)!!.deletePending)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun discoveryCreatesSeparateProfileWhenServerAtSameUrlWasReinstalled() = runBlocking {
        val fixture = Fixture()
        try {
            val previousId = OfflineProfile.identity("old-instance", fixture.session.userId)
            fixture.dao.putProfile(
                OfflineProfile(
                    previousId,
                    "old-instance",
                    fixture.session.userId,
                    fixture.session.serverUrl,
                    fixture.session.username,
                )
            )
            val repository =
                OfflineCatalogRepository(fixture.database) { _, _, _, _ ->
                    JSONObject()
                        .put("enabled", true)
                        .put("contractVersion", "offline-v1")
                        .put("serverInstanceId", "server-instance")
                }

            val discovered = repository.discover(fixture.session).first

            assertNotEquals(previousId, discovered.id)
            assertEquals("server-instance", discovered.serverInstance)
            assertNotNull(fixture.dao.profile(previousId))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun profileRemovalDeletesOnlyItsMediaAndAccountState() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.dao.putProfile(fixture.profile(fixture.profileId))
            val otherProfile = OfflineProfile.identity("other-server", "other-account")
            fixture.dao.putProfile(fixture.profile(otherProfile))
            val files = fixture.addAsset(fixture.profileId, "movie-one")
            fixture.dao.putItems(listOf(fixture.catalogItem(otherProfile, "other-movie")))

            fixture.storage.removeProfile(fixture.profileId)

            assertNull(fixture.dao.profile(fixture.profileId))
            assertNull(fixture.dao.asset(fixture.profileId, files.assetId))
            assertNull(fixture.dao.item(fixture.profileId, "movie-one"))
            assertTrue(fixture.dao.supplements(fixture.profileId, files.assetId).isEmpty())
            assertFalse(files.media.isFile)
            assertFalse(files.artwork.isFile)
            assertNotNull(
                "another account profile must be isolated",
                fixture.dao.profile(otherProfile),
            )
            assertNotNull(fixture.dao.item(otherProfile, "other-movie"))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun activePlaybackLeaseDefersFileRemovalUntilTheLeaseCloses() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.dao.putProfile(fixture.profile(fixture.profileId))
            val files = fixture.addAsset(fixture.profileId, "movie-one")
            val lease = fixture.storage.acquire(fixture.profileId, files.assetId)
            try {
                fixture.storage.removeProfile(fixture.profileId)
                val pending = fixture.dao.profile(fixture.profileId)!!
                assertTrue(pending.locked)
                assertTrue(pending.deletePending)
                assertEquals(
                    "pending_delete",
                    fixture.dao.asset(fixture.profileId, files.assetId)!!.state,
                )
                assertTrue(files.media.isFile)
            } finally {
                lease.close()
            }

            withTimeout(5_000) {
                while (fixture.dao.profile(fixture.profileId) != null) delay(20)
            }
            assertFalse(files.media.exists())
            assertFalse(files.artwork.exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun profileRemovalPagesPastRowsDeletedBeforeAnActiveLease() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.dao.putProfile(fixture.profile(fixture.profileId))
            val assets =
                (0 until 125).map { index ->
                    val id = sha256("bulk-asset:$index".toByteArray())
                    OfflineAsset(
                        profile = fixture.profileId,
                        id = id,
                        item = "movie-$index",
                        quality = "original",
                        sourceId = "source",
                        sourceRevision = "revision",
                        size = 0,
                        sha256 = sha256(byteArrayOf()),
                        mimeType = "video/mp4",
                        descriptor = "{}",
                        relativePath = fixture.storage.paths.relative(fixture.profileId, id),
                        state = "complete",
                    )
                }
            assets.forEach { fixture.dao.putAsset(it) }
            val leasedAsset = assets.minBy { it.id }
            val media = fixture.storage.paths.resolve(leasedAsset.relativePath)
            media.parentFile!!.mkdirs()
            media.writeBytes(byteArrayOf())
            val lease = fixture.storage.acquire(fixture.profileId, leasedAsset.id)

            fixture.storage.removeProfile(fixture.profileId)

            assertEquals(1, fixture.dao.assetCount(fixture.profileId))
            assertTrue(media.isFile)
            lease.close()
            withTimeout(5_000) {
                while (fixture.dao.profile(fixture.profileId) != null) delay(20)
            }
            assertFalse(media.exists())
        } finally {
            fixture.close()
        }
    }

    private class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        val dao = database.offline()
        val profileId = OfflineProfile.identity("server-instance", "account")
        private val root = File(context.cacheDir, "offline-profile-${UUID.randomUUID()}")
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val storage = OfflineStorage(database, root, scope)
        val session = AuthSession("https://example.invalid", "token", "account", "User")

        fun profile(id: String, locked: Boolean = false) =
            if (id == profileId)
                OfflineProfile(
                    id,
                    "server-instance",
                    "account",
                    "https://example.invalid",
                    "User",
                    locked = locked,
                )
            else
                OfflineProfile(
                    id,
                    "other-server",
                    "other-account",
                    "https://other.invalid",
                    "Other",
                    locked = locked,
                )

        suspend fun addAsset(profile: String, itemId: String): StoredFiles {
            val bytes = "media:$itemId".toByteArray()
            val assetId = sha256("asset:$profile:$itemId".toByteArray())
            val relative = storage.paths.relative(profile, assetId)
            val media = storage.paths.resolve(relative)
            media.parentFile!!.mkdirs()
            media.writeBytes(bytes)
            val artworkBytes = "artwork:$itemId".toByteArray()
            val artworkPath = storage.paths.relative(profile, sha256(artworkBytes), "art")
            val artwork = storage.paths.resolve(artworkPath)
            artwork.parentFile!!.mkdirs()
            artwork.writeBytes(artworkBytes)
            dao.putItems(listOf(catalogItem(profile, itemId)))
            dao.putSelection(
                OfflineSelection(profile, "selection-$itemId", itemId, "catalog", itemId, "{}")
            )
            dao.putAsset(
                OfflineAsset(
                    profile = profile,
                    id = assetId,
                    item = itemId,
                    quality = "original",
                    sourceId = "source",
                    sourceRevision = "revision",
                    size = bytes.size.toLong(),
                    sha256 = sha256(bytes),
                    mimeType = "video/mp4",
                    descriptor = "{}",
                    relativePath = relative,
                    state = "complete",
                )
            )
            dao.putReference(OfflineAssetReference(profile, "selection-$itemId", assetId))
            dao.putSupplements(
                listOf(
                    OfflineSupplement(
                        profile,
                        assetId,
                        "art:$itemId:Primary",
                        artworkPath,
                        "art-revision",
                        "{}",
                    )
                )
            )
            return StoredFiles(assetId, media, artwork)
        }

        fun catalogItem(profile: String, id: String) =
            OfflineCatalogItem(
                profile = profile,
                id = id,
                libraryId = "library",
                parentId = null,
                type = "movie",
                name = id,
                payload =
                    JSONObject().put("id", id).put("type", "movie").put("name", id).toString(),
            )

        override fun close() {
            scope.cancel()
            database.close()
            root.deleteRecursively()
        }
    }

    private data class StoredFiles(val assetId: String, val media: File, val artwork: File)

    companion object {
        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
    }
}

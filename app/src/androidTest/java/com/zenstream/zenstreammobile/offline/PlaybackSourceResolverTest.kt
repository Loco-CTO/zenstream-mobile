package com.zenstream.zenstreammobile.offline

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.model.*
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceResolverTest {
    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val root = File(context.cacheDir, "offline-resolver-" + java.util.UUID.randomUUID())
        val profile =
            OfflineProfile(
                OfflineProfile.identity("server", "user"),
                "server",
                "user",
                "https://example.invalid",
                "User",
            )
        val catalog =
            OfflineCatalogRepository(db) { _, _, _, _ -> error("Local playback attempted HTTP") }
        val storage = OfflineStorage(db, root, scope, freeBytes = { 8 * OFFLINE_FREE_RESERVE })
        var policy =
            ConnectionState(
                initialized = true,
                profile = profile.id,
                reachability = ServerReachability.REACHABLE,
            )
        var requests = 0
        val resolver =
            PlaybackSourceResolver(catalog, storage, { policy }) { _, _, _ ->
                requests++
                PlaybackData(
                    MediaItem("item", "Remote"),
                    MediaSource("source", url = "/media"),
                    emptyList(),
                    emptyList(),
                )
            }
        val session = AuthSession(profile.serverUrl, "expired-token", profile.account, "User")

        suspend fun seed(id: String = "a".repeat(64), value: String = "data"): OfflineAsset {
            db.offline().putProfile(profile)
            catalog.ingestItems(
                profile.id,
                listOf(
                    org.json
                        .JSONObject()
                        .put("id", "item")
                        .put("type", "movie")
                        .put("metadata", org.json.JSONObject().put("title", "Saved"))
                ),
            )
            db.offline()
                .putTransfer(
                    OfflineTransfer(
                        profile.id,
                        id,
                        "selection",
                        "item",
                        "{}",
                        state = "publishing",
                        assetId = id,
                        bytes = value.length.toLong(),
                        total = value.length.toLong(),
                    )
                )
            storage.staging(profile.id, id).let {
                it.parentFile!!.mkdirs()
                it.writeText(value)
            }
            return storage.publish(
                OfflineAsset(
                    profile.id,
                    id,
                    "item",
                    "original",
                    "source",
                    "revision",
                    value.length.toLong(),
                    sha256(storage.staging(profile.id, id)),
                    "video/mp4",
                    """{"durationSeconds":60,"trackManifest":[{"index":0,"type":"video","codec":"h264"}]}""",
                    "",
                ),
                id,
            )
        }

        override fun close() {
            runBlocking { requireNotNull(scope.coroutineContext[Job]).cancelAndJoin() }
            db.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun retainedDownloadsWinOnlineAndWithNoOnlineCredentials() = runBlocking {
        Fixture().use { fixture ->
            fixture.seed()
            fixture.resolver.resolve("item", fixture.session).use { source ->
                assertTrue(source is ResolvedPlaybackSource.Local)
                assertTrue(source.uri.startsWith("file:"))
                assertEquals("Saved", source.data.item.name)
            }
            fixture.policy =
                fixture.policy.copy(
                    mode = ConnectionMode.OFFLINE,
                    authentication = OnlineAuthentication.NEEDS_SIGN_IN,
                    reachability = ServerReachability.UNREACHABLE,
                )
            fixture.resolver.resolve("item", null).use {
                assertTrue(it is ResolvedPlaybackSource.Local)
            }
            assertEquals(0, fixture.requests)
        }
    }

    @Test
    fun missingAndSameLengthCorruptFilesNeverFallBackToStreaming() = runBlocking {
        Fixture().use { fixture ->
            val asset = fixture.seed()
            val file = fixture.storage.paths.resolve(asset.relativePath)
            file.writeText("evil")
            assertTrue(file.setLastModified(file.lastModified() + 2000))
            try {
                fixture.resolver.resolve("item", fixture.session)
                fail("Corrupt media was accepted")
            } catch (failure: LocalPlaybackUnavailable) {
                assertEquals(LocalPlaybackFailure.DAMAGED, failure.reason)
            }
            assertEquals("corrupt", fixture.db.offline().asset(fixture.profile.id, asset.id)?.state)
            assertEquals(0, fixture.requests)
        }
        Fixture().use { fixture ->
            val asset = fixture.seed()
            assertTrue(fixture.storage.paths.resolve(asset.relativePath).delete())
            try {
                fixture.resolver.resolve("item", fixture.session)
                fail("Missing media was accepted")
            } catch (failure: LocalPlaybackUnavailable) {
                assertEquals(LocalPlaybackFailure.DAMAGED, failure.reason)
            }
            assertEquals(0, fixture.requests)
        }
    }

    @Test
    fun explicitStreamRequiresOnlineModeAndKeepsRemoteOriginValidation() = runBlocking {
        val previouslyAllowed = OfflineNetworkPolicy.allowed
        OfflineNetworkPolicy.publish(true)
        try {
            Fixture().use { fixture ->
                fixture.seed()
                fixture.resolver
                    .resolve("item", fixture.session, preference = PlaybackSourcePreference.STREAM)
                    .use {
                        assertTrue(it is ResolvedPlaybackSource.Remote)
                        assertEquals("https://example.invalid/media", it.uri)
                    }
                assertEquals(1, fixture.requests)
                fixture.policy = fixture.policy.copy(mode = ConnectionMode.OFFLINE)
                try {
                    fixture.resolver.resolve(
                        "item",
                        fixture.session,
                        preference = PlaybackSourcePreference.STREAM,
                    )
                    fail("Offline streaming was accepted")
                } catch (failure: LocalPlaybackUnavailable) {
                    assertEquals(LocalPlaybackFailure.NETWORK_DISABLED, failure.reason)
                }
                assertEquals(1, fixture.requests)
            }
        } finally {
            OfflineNetworkPolicy.publish(previouslyAllowed)
        }
    }

    @Test
    fun localPathsAreConfinedToTheirAccountAndLockedProfilesCannotPlay() = runBlocking {
        Fixture().use { fixture ->
            val asset = fixture.seed()
            fixture.db
                .offline()
                .putAsset(
                    asset.copy(
                        relativePath = fixture.storage.paths.relative("b".repeat(64), asset.id)
                    )
                )
            try {
                fixture.resolver.resolve("item", fixture.session)
                fail("Cross-profile media path was accepted")
            } catch (failure: LocalPlaybackUnavailable) {
                assertEquals(LocalPlaybackFailure.DAMAGED, failure.reason)
            }
            fixture.db.offline().putProfile(fixture.profile.copy(locked = true))
            try {
                fixture.resolver.resolve("item", null)
                fail("Locked profile played")
            } catch (failure: LocalPlaybackUnavailable) {
                assertEquals(LocalPlaybackFailure.LOCKED, failure.reason)
            }
            assertEquals(0, fixture.requests)
        }
    }

    @Test
    fun anotherAccountAndUnreachableServerCannotSelectTheRetainedFile() = runBlocking {
        Fixture().use { fixture ->
            fixture.seed()
            try {
                fixture.resolver.resolve(
                    "item",
                    fixture.session.copy(userId = "other"),
                    preference = PlaybackSourcePreference.DOWNLOAD_ONLY,
                )
                fail("Another account selected a retained download")
            } catch (failure: LocalPlaybackUnavailable) {
                assertEquals(LocalPlaybackFailure.UNAVAILABLE, failure.reason)
            }
            fixture.policy = fixture.policy.copy(reachability = ServerReachability.UNREACHABLE)
            try {
                fixture.resolver.resolve("unknown", fixture.session)
                fail("Unreachable server was used")
            } catch (failure: LocalPlaybackUnavailable) {
                assertEquals(LocalPlaybackFailure.UNAVAILABLE, failure.reason)
            }
            assertEquals(0, fixture.requests)
        }
    }
}

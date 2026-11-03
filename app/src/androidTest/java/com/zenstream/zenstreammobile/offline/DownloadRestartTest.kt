package com.zenstream.zenstreammobile.offline

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.data.CatalogApi
import com.zenstream.zenstreammobile.model.AuthSession
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class DownloadRestartTest {
    private val originalMarker = "original"
    private val etagHeader = "ETag"

    @Test
    fun reopeningKeepsTheExistingJobButReplacesChangedProfileOrNetworkConstraints() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scheduler =
            context
                .getSystemService(JobScheduler::class.java)
                .forNamespace("zenstream.offline.restart-test")
        val currentProfile = "profile"
        val otherProfile = "other"
        fun job(profile: String, wifiOnly: Boolean = true, marker: Boolean = false): JobInfo =
            JobInfo.Builder(1, ComponentName(context, DownloadJobService::class.java))
                .setMinimumLatency(60_000)
                .setRequiredNetwork(
                    NetworkRequest.Builder()
                        .apply {
                            if (wifiOnly)
                                addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                        }
                        .build()
                )
                .setExtras(
                    PersistableBundle().apply {
                        putString(DownloadExecution.PROFILE, profile)
                        putBoolean(originalMarker, marker)
                    }
                )
                .build()
        try {
            assertTrue(scheduleTransferJob(scheduler, job(currentProfile, marker = true)))
            assertTrue(scheduleTransferJob(scheduler, job(currentProfile)))
            assertTrue(requireNotNull(scheduler.getPendingJob(1)).extras.getBoolean(originalMarker))
            assertTrue(scheduleTransferJob(scheduler, job(otherProfile)))
            assertEquals(
                otherProfile,
                requireNotNull(scheduler.getPendingJob(1))
                    .extras
                    .getString(DownloadExecution.PROFILE),
            )
            assertTrue(scheduleTransferJob(scheduler, job(otherProfile, wifiOnly = false)))
            assertEquals(
                job(otherProfile, wifiOnly = false).requiredNetwork,
                requireNotNull(scheduler.getPendingJob(1)).requiredNetwork,
            )
            scheduler.cancel(1)
            assertTrue(scheduleTransferJob(scheduler, job(otherProfile, marker = true)))
            assertTrue(requireNotNull(scheduler.getPendingJob(1)).extras.getBoolean(originalMarker))
        } finally {
            scheduler.cancelAll()
        }
    }

    @Test
    fun cancellingWhileWaitingForHeadersReleasesTheTransferRunner() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.start()
            val api = CatalogApi(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build())
            val session =
                AuthSession(server.url("").toString().trimEnd('/'), "token", "user", "User")
            val running =
                launch(Dispatchers.IO) {
                    api.offlineBinaryRequest(
                        session,
                        "/api/offline/assets/test/content",
                        emptyMap(),
                    ) {}
                }
            try {
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                running.cancel()
                withTimeout(2_000) { running.join() }
            } finally {
                running.cancel()
                server.close()
                running.join()
            }
        }
    }

    @Test
    fun cancelledBodyReadCanResumeFromTheStagingFileAfterDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "download-restart-${System.nanoTime()}"
        val root = File(context.cacheDir, name).apply { mkdirs() }
        var db = Room.databaseBuilder(context, OfflineDatabase::class.java, name).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val profileId = "a".repeat(64)
        val assetId = "b".repeat(64)
        val selectionId = "selection"
        val transferId = "transfer"
        val itemId = "movie"
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val source = File(root, "source").apply { writeBytes(bytes) }
        val digest = sha256(source)
        val etag = "\"$digest\""
        MockWebServer().use { server ->
            server.start()
            val session =
                AuthSession(server.url("").toString().trimEnd('/'), "token", "user", "User")
            val descriptor =
                JSONObject()
                    .put("id", assetId)
                    .put("state", "ready")
                    .put("sizeBytes", bytes.size)
                    .put("sha256", digest)
                    .put("profile", "original")
                    .put("sourceId", "source")
                    .put("sourceRevision", "revision")
                    .put("mimeType", "video/mp4")
                    .put("contentUrl", "/api/offline/assets/$assetId/content")
                    .put("bundleUrl", "/api/offline/assets/$assetId/bundle")
            val api = CatalogApi(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build())
            fun coordinator(storage: OfflineStorage): DownloadCoordinator {
                val request: OfflineRequest = { _, path, _, _ ->
                    when {
                        path.endsWith("capabilities") ->
                            JSONObject().put("serverInstanceId", "server")
                        path.contains("/bundle") -> JSONObject()
                        else -> JSONObject(descriptor.toString())
                    }
                }
                return DownloadCoordinator(
                    OfflineCatalogRepository(db, request),
                    storage,
                    request,
                    binary = api::offlineBinaryRequest,
                    activeSession = { session },
                )
            }
            var storage = OfflineStorage(db, root, scope, freeBytes = { 8 * OFFLINE_FREE_RESERVE })
            var running: Job? = null
            try {
                db.offline()
                    .putProfile(
                        OfflineProfile(profileId, "server", "user", session.serverUrl, "User")
                    )
                db.offline()
                    .putSelection(
                        OfflineSelection(
                            profileId,
                            selectionId,
                            itemId,
                            itemId,
                            "Movie",
                            "{}",
                            state = "queued",
                        )
                    )
                db.offline()
                    .putTransfer(OfflineTransfer(profileId, transferId, selectionId, itemId, "{}"))
                // Send a checkpoint, then leave the body open as a connection can during app
                // closure.
                server.enqueue(
                    MockResponse()
                        .setBody(okio.Buffer().write(bytes, 0, 1024 * 1024))
                        .setHeader("Content-Length", bytes.size)
                        .setHeader(etagHeader, etag)
                )
                val first = coordinator(storage)
                running = launch(Dispatchers.IO) { first.runOnce(session, profileId) }
                withTimeout(10_000) {
                    while (
                        (db.offline().transfer(profileId, transferId)?.bytes ?: 0) < 1024 * 1024
                    ) delay(20)
                }
                running.cancel()
                withTimeout(2_000) { running.join() }
                assertEquals("transferring", db.offline().transfer(profileId, transferId)?.state)
                val offset = storage.staging(profileId, assetId).length()
                assertTrue(offset in 1 until bytes.size.toLong())
                db.close()
                db = Room.databaseBuilder(context, OfflineDatabase::class.java, name).build()
                storage = OfflineStorage(db, root, scope, freeBytes = { 8 * OFFLINE_FREE_RESERVE })
                server.enqueue(
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader(etagHeader, etag)
                        .setHeader(
                            "Content-Range",
                            "bytes $offset-${bytes.lastIndex}/${bytes.size}",
                        )
                        .setBody(
                            okio.Buffer().write(bytes, offset.toInt(), bytes.size - offset.toInt())
                        )
                )
                withTimeout(10_000) { coordinator(storage).runOnce(session, profileId) }
                server.takeRequest(5, TimeUnit.SECONDS)
                val resumed = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertEquals("bytes=$offset-", resumed.getHeader("Range"))
                assertEquals(etag, resumed.getHeader("If-Range"))
                assertEquals("complete", db.offline().transfer(profileId, transferId)?.state)
                val asset = requireNotNull(db.offline().asset(profileId, assetId))
                assertEquals(digest, sha256(storage.paths.resolve(asset.relativePath)))
            } finally {
                running?.cancel()
                server.close()
                running?.join()
                scope.cancel()
                db.close()
                context.deleteDatabase(name)
                root.deleteRecursively()
            }
        }
    }
}

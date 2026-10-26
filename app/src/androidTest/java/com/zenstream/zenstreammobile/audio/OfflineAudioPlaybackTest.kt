package com.zenstream.zenstreammobile.audio

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.MainActivity
import com.zenstream.zenstreammobile.model.*
import com.zenstream.zenstreammobile.offline.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

@UnstableApi
class OfflineAudioPlaybackTest {
    @Test
    fun coldQueuePlaysLocalPcmWithoutCredentialsAndExposesOfflineMediaSession() =
        exerciseLocalAudio("wav")

    @Test fun downloadedFlacPlaysWithoutOnlineCredentials() = exerciseLocalAudio("flac")

    @Test fun compactAacPlaysWithoutOnlineCredentials() = exerciseLocalAudio("m4a")

    @Test
    fun originalMultipleAudioTracksHonorsTheDownloadedSelection() = exerciseLocalAudio("multi")

    private fun exerciseLocalAudio(format: String) = runBlocking {
        assumeTrue("Use the isolated offline QA build", OfflineRuntime.ENABLED)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = OfflineRuntime.get(context)
        val before = runtime.connection.state.first { it.initialized }
        assumeTrue(
            "QA must not replace an authenticated session",
            runtime.sessionStore.session.first() == null,
        )
        val profile =
            OfflineProfile(
                OfflineProfile.identity("audio-service-test", "user"),
                "audio-service-test",
                "user",
                "https://example.invalid",
                "Offline audio test",
            )
        val dao = runtime.database.offline()
        val assetId = "c".repeat(64)
        var activity: ActivityScenario<MainActivity>? = null
        var coordinator: AudioPlayerCoordinator? = null
        var browser: MediaBrowser? = null
        try {
            dao.putProfile(profile)
            dao.putSelection(
                OfflineSelection(
                    profile.id,
                    "audio-service-selection",
                    "local-album",
                    "catalog",
                    "Offline audio",
                    "{}",
                    state = "queued",
                    itemCount = 1,
                )
            )
            runtime.catalog.ingestItems(
                profile.id,
                listOf(
                    JSONObject()
                        .put("id", "local-album")
                        .put("type", "release")
                        .put("metadata", JSONObject().put("title", "Local album")),
                    JSONObject()
                        .put("id", "local-track")
                        .put("type", "track")
                        .put("parentId", "local-album")
                        .put("metadata", JSONObject().put("title", "Local silent PCM")),
                ),
            )
            val wav =
                if (format == "wav") wave(12)
                else
                    InstrumentationRegistry.getInstrumentation()
                        .context
                        .assets
                        .open(
                            if (format == "multi") "offline/multi.m4a"
                            else "offline/silence.$format"
                        )
                        .use { it.readBytes() }
            dao.putTransfer(
                OfflineTransfer(
                    profile.id,
                    "audio-service-transfer",
                    "audio-service-selection",
                    "local-track",
                    "{}",
                    state = "publishing",
                    assetId = assetId,
                    bytes = wav.size.toLong(),
                    total = wav.size.toLong(),
                )
            )
            runtime.storage.staging(profile.id, assetId).let {
                it.parentFile!!.mkdirs()
                it.writeBytes(wav)
            }
            val lyrics =
                JSONObject()
                    .put("source", "local")
                    .put("timed", false)
                    .put(
                        "lines",
                        JSONArray().put(JSONObject().put("text", "Saved lyrics")),
                    )
            val descriptor =
                JSONObject()
                    .put("durationSeconds", 12)
                    .put(
                        "trackManifest",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("index", 0)
                                    .put("type", "audio")
                                    .put(
                                        "codec",
                                        when (format) {
                                            "flac" -> "flac"
                                            "m4a",
                                            "multi" -> "aac"
                                            else -> "pcm_s16le"
                                        },
                                    )
                                    .put("sampleRate", if (format == "wav") 8000 else 48000)
                            ),
                    )
                    .put("bundle", JSONObject().put("lyrics", lyrics))
            if (format == "multi") {
                descriptor.getJSONArray("trackManifest").getJSONObject(0).put("language", "eng")
                descriptor
                    .getJSONArray("trackManifest")
                    .put(
                        JSONObject()
                            .put("index", 1)
                            .put("type", "audio")
                            .put("codec", "aac")
                            .put("sampleRate", 48000)
                            .put("language", "jpn")
                    )
                descriptor.put("downloadOptions", JSONObject().put("audioStreamId", 1))
            }
            runtime.storage.publish(
                OfflineAsset(
                    profile.id,
                    assetId,
                    "local-track",
                    if (format == "m4a") "audio-128k" else "original",
                    "source",
                    "revision",
                    wav.size.toLong(),
                    digest(wav),
                    when (format) {
                        "flac" -> "audio/flac"
                        "m4a",
                        "multi" -> "audio/mp4"
                        else -> "audio/wav"
                    },
                    descriptor.toString(),
                    "",
                ),
                "audio-service-transfer",
            )
            val artwork =
                ByteArrayOutputStream()
                    .also { output ->
                        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(android.graphics.Color.DKGRAY)
                            compress(Bitmap.CompressFormat.PNG, 100, output)
                            recycle()
                        }
                    }
                    .toByteArray()
            val artDigest = digest(artwork)
            val artPath = runtime.storage.paths.relative(profile.id, artDigest, "art")
            runtime.storage.paths.resolve(artPath).let {
                it.parentFile!!.mkdirs()
                it.writeBytes(artwork)
            }
            dao.putSupplements(
                listOf(
                    OfflineSupplement(
                        profile.id,
                        assetId,
                        "art:local-track:Primary",
                        artPath,
                        artDigest,
                        "{}",
                    )
                )
            )
            runtime.connection.selectProfile(profile.id)
            runtime.connection.setMode(ConnectionMode.OFFLINE)
            val identity = requireNotNull(runtime.audio.identity(profile.id, null))
            runtime.audio.save(
                identity,
                AudioQueueSnapshot(
                    serverUrl = profile.serverUrl,
                    userId = profile.account,
                    entries =
                        listOf(
                            AudioQueueEntry(
                                "stable-entry",
                                MediaItem(
                                    "local-track",
                                    "Local silent PCM",
                                    type = "Audio",
                                    durationSeconds = 12.0,
                                ),
                            )
                        ),
                    positionSeconds = 1.25,
                    durationSeconds = 12.0,
                    offlineOnly = true,
                ),
            )
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            coordinator = withContext(Dispatchers.Main) { AudioPlayerCoordinator(context, profile) }
            val restored =
                withTimeout(15000) {
                    AudioServiceBridge.state.first { it.currentEntry?.entryId == "stable-entry" }
                }
            assertEquals(12L, restored.durationSeconds)
            assertTrue(restored.offlineOnly)
            withContext(Dispatchers.Main) { coordinator.togglePlayback() }
            val playing =
                withTimeout(20000) {
                    AudioServiceBridge.state.first {
                        it.playbackMode == "local" && it.isPlaying && it.positionMillis > 1800
                    }
                }
            assertNull(playing.error)
            assertEquals(profile.id, playing.offlineProfileId)
            val future =
                withContext(Dispatchers.Main) {
                    MediaBrowser.Builder(
                            context,
                            SessionToken(
                                context,
                                ComponentName(context, AudioPlaybackService::class.java),
                            ),
                        )
                        .buildAsync()
                }
            browser = withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
            val rootFuture = withContext(Dispatchers.Main) { browser.getLibraryRoot(null) }
            withContext(Dispatchers.IO) { rootFuture.get(10, TimeUnit.SECONDS) }
            val childrenFuture =
                withContext(Dispatchers.Main) {
                    browser.getChildren("zenstream:offline:tracks", 0, 100, null)
                }
            val children = withContext(Dispatchers.IO) { childrenFuture.get(10, TimeUnit.SECONDS) }
            assertEquals(1, children.value?.size)
            val artUri = requireNotNull(children.value?.first()?.mediaMetadata?.artworkUri)
            assertEquals("content", artUri.scheme)
            context.contentResolver.openInputStream(artUri).use {
                assertEquals(artwork.toList(), it!!.readBytes().toList())
            }
            assertEquals(
                "Saved lyrics",
                runtime.audio.lyrics(profile.id, "local-track")?.lines?.single()?.text,
            )
            assertNull(runtime.sessionStore.session.first())
            assertFalse(OfflineNetworkPolicy.allowed)
            if (format == "multi")
                withTimeout(10000) {
                    AudioServiceBridge.state.first {
                        languageKey(it.sourceAudioLanguage.orEmpty()) == "ja"
                    }
                }
            if (format == "wav") {
                val available =
                    MediaItem("local-track", "Saved", type = "Audio", durationSeconds = 12.0)
                withContext(Dispatchers.Main) {
                    coordinator.playTracks(
                        listOf(
                            available,
                            MediaItem("missing-track", "Unavailable", type = "Audio"),
                            available,
                        )
                    )
                }
                withTimeout(15000) {
                    AudioServiceBridge.state.first {
                        it.queue.size == 3 && it.currentIndex == 0 && it.isPlaying
                    }
                }
                withContext(Dispatchers.Main) { coordinator.seekTo(11) }
                val advanced =
                    withTimeout(15000) {
                        AudioServiceBridge.state.first {
                            it.currentIndex == 2 && it.isPlaying && it.notice != null
                        }
                    }
                assertEquals(3, advanced.queue.size)
                val missing = advanced.queue[1].entryId
                withContext(Dispatchers.Main) { coordinator.playQueueEntry(missing) }
                val explicit =
                    withTimeout(10000) {
                        AudioServiceBridge.state.first {
                            it.currentEntry?.entryId == missing && it.error != null
                        }
                    }
                assertFalse(explicit.isPlaying)
                assertEquals(3, explicit.queue.size)
            }
        } finally {
            withContext(Dispatchers.Main) { coordinator?.stopAndClear() }
            withTimeoutOrNull(5000) { AudioServiceBridge.state.first { it.queue.isEmpty() } }
            withContext(Dispatchers.Main) {
                browser?.release()
                coordinator?.release()
            }
            context.stopService(Intent(context, AudioPlaybackService::class.java))
            runtime.storage.removeSelection(profile.id, "audio-service-selection")
            for (table in
                listOf(
                    "offline_audio_queues",
                    "offline_catalog",
                    "offline_states",
                    "offline_transfers",
                    "offline_selections",
                )) runtime.database.openHelper.writableDatabase.execSQL(
                "DELETE FROM $table WHERE profile=?",
                arrayOf(profile.id),
            )
            runtime.database.offline().deleteProfileRecord(profile.id)
            runtime.connection.selectProfile(before.profile)
            runtime.connection.setMode(before.mode)
            activity?.close()
        }
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun wave(seconds: Int): ByteArray {
        val data = 8000 * seconds * 2
        return ByteBuffer.allocate(44 + data)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray())
            .putInt(36 + data)
            .put("WAVEfmt ".toByteArray())
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(8000)
            .putInt(16000)
            .putShort(2)
            .putShort(16)
            .put("data".toByteArray())
            .putInt(data)
            .array()
    }
}

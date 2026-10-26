package com.zenstream.zenstreammobile.offline

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.PlaybackActivity
import com.zenstream.zenstreammobile.VideoPlaybackSessions
import com.zenstream.zenstreammobile.data.CatalogApi
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.playbackIntent
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class OfflineVideoPlaybackTest {
    @Test
    fun media3RendersAndSeeksDownloadedVideoWithLocalSubtitles() = exercise(PlayerEngine.MEDIA3)

    @Test fun mpvRendersAndSeeksDownloadedVideoWithLocalSubtitles() = exercise(PlayerEngine.MPV)

    private fun exercise(engine: PlayerEngine): Unit = runBlocking {
        assumeTrue("Use the isolated offline QA build", OfflineRuntime.ENABLED)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val runtime = OfflineRuntime.get(context)
        assumeTrue(runtime.sessionStore.session.first() == null)
        val previous = runtime.connection.state.first { it.initialized }
        val preferences = CatalogRepository(CatalogApi(), runtime.sessionStore, offline = runtime)
        val previousEngine = preferences.playerEngine.first()
        val profile =
            OfflineProfile(
                OfflineProfile.identity("video-service-test", "user"),
                "video-service-test",
                "user",
                "https://example.invalid",
                "Offline video test",
            )
        val dao = runtime.database.offline()
        val asset = "d".repeat(64)
        var activity: ActivityScenario<PlaybackActivity>? = null
        try {
            preferences.savePlayerEngine(engine)
            dao.putProfile(profile)
            dao.putSelection(
                OfflineSelection(
                    profile.id,
                    "video-selection",
                    "video",
                    "catalog",
                    "Video",
                    "{}",
                    state = "queued",
                    itemCount = 1,
                )
            )
            runtime.catalog.ingestItems(
                profile.id,
                listOf(
                    JSONObject()
                        .put("id", "video")
                        .put("type", "movie")
                        .put("metadata", JSONObject().put("title", "Local video fixture"))
                ),
            )
            val bytes =
                instrumentation.context.assets.open("offline/video.mp4").use { it.readBytes() }
            dao.putTransfer(
                OfflineTransfer(
                    profile.id,
                    "video-transfer",
                    "video-selection",
                    "video",
                    "{}",
                    state = "publishing",
                    assetId = asset,
                    bytes = bytes.size.toLong(),
                    total = bytes.size.toLong(),
                )
            )
            runtime.storage.staging(profile.id, asset).apply {
                parentFile!!.mkdirs()
                writeBytes(bytes)
            }
            val manifest =
                JSONArray()
                    .put(
                        JSONObject()
                            .put("index", 0)
                            .put("type", "video")
                            .put("codec", "h264")
                            .put("isHdr", false)
                    )
                    .put(
                        JSONObject()
                            .put("index", 1)
                            .put("type", "audio")
                            .put("codec", "aac")
                            .put("language", "eng")
                    )
                    .put(
                        JSONObject()
                            .put("index", 2)
                            .put("type", "audio")
                            .put("codec", "aac")
                            .put("language", "jpn")
                    )
            val descriptor =
                JSONObject()
                    .put("durationSeconds", 12)
                    .put("trackManifest", manifest)
                    .put("downloadOptions", JSONObject().put("audioStreamId", 2))
                    .put(
                        "bundle",
                        JSONObject()
                            .put(
                                "chapters",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("startSeconds", 0)
                                            .put("name", "Local chapter")
                                    ),
                            )
                            .put(
                                "segments",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("type", "intro")
                                            .put("startSeconds", 0)
                                            .put("endSeconds", 1)
                                    ),
                            ),
                    )
            runtime.storage.publish(
                OfflineAsset(
                    profile.id,
                    asset,
                    "video",
                    "original",
                    "source",
                    "revision",
                    bytes.size.toLong(),
                    digest(bytes),
                    "video/mp4",
                    descriptor.toString(),
                    "",
                ),
                "video-transfer",
            )
            val subtitle =
                "WEBVTT\n\n00:00:00.000 --> 00:00:11.900\nSaved local subtitle\n".toByteArray()
            val relative = runtime.storage.paths.relative(profile.id, digest(subtitle), "vtt")
            runtime.storage.paths.resolve(relative).apply {
                parentFile!!.mkdirs()
                writeBytes(subtitle)
            }
            dao.putSupplements(
                listOf(
                    OfflineSupplement(
                        profile.id,
                        asset,
                        "subtitle:9",
                        relative,
                        digest(subtitle),
                        "{\"index\":9,\"language\":\"eng\",\"title\":\"Saved English\"}",
                    )
                )
            )
            runtime.connection.selectProfile(profile.id)
            runtime.connection.setMode(ConnectionMode.OFFLINE)
            activity =
                ActivityScenario.launch(
                    playbackIntent(
                        context,
                        "video",
                        "Local video fixture",
                        offlineProfileId = profile.id,
                    )
                )
            activity.onActivity {
                it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            val model =
                withTimeout(15000) {
                    while (VideoPlaybackSessions.active?.playbackModel == null) delay(25)
                    requireNotNull(VideoPlaybackSessions.active?.playbackModel)
                }
            val ready =
                awaitState("initial playback", model) {
                    it.local && it.engine.ready && it.engine.positionSeconds > .5
                }
            assertNull(ready.error)
            assertEquals(engine, ready.engineType)
            assertEquals(2, ready.selectedAudio)
            assertNull(ready.playback?.sessionId)
            assertNull(ready.playback?.viewerSessionId)
            assertNull(ready.playback?.accessExpiresIn)
            assertEquals("Local chapter", ready.playback?.item?.chapters?.firstOrNull()?.name)
            instrumentation.runOnMainSync { model.chooseSubtitle(9) }
            withTimeout(5000) {
                model.uiState.first {
                    it.subtitleCues.any { cue -> cue.text == "Saved local subtitle" }
                }
            }
            assertRendered(requireNotNull(activity), engine, "start")
            instrumentation.runOnMainSync {
                model.seekTo(8.0)
                model.chooseAudio(requireNotNull(model.uiState.value.playback).audioTracks.first())
            }
            withTimeout(10000) {
                model.uiState.first { it.engine.positionSeconds >= 7.8 && it.engine.ready }
            }
            assertEquals(1, model.uiState.value.selectedAudio)
            assertRendered(requireNotNull(activity), engine, "seek")
            instrumentation.runOnMainSync { model.tryOtherLocalEngine() }
            awaitState("other-engine handoff", model) {
                it.engineType != engine && it.engine.ready && it.engine.positionSeconds >= 7.8
            }
            assertRendered(requireNotNull(activity), engine, "other")
            dao.putProfile(profile.copy(locked = true))
            withTimeout(5000) { model.uiState.first { it.closeRequested } }
            instrumentation.runOnMainSync { VideoPlaybackSessions.close() }
            activity?.close()
            assertEquals(
                "Video playback session teardown retained its media lease.",
                0,
                runtime.storage.leaseCount(profile.id, asset),
            )
        } finally {
            instrumentation.runOnMainSync { VideoPlaybackSessions.close() }
            activity?.close()
            runtime.storage.removeSelection(profile.id, "video-selection")
            for (table in
                listOf(
                    "offline_catalog",
                    "offline_states",
                    "offline_transfers",
                    "offline_selections",
                )) runtime.database.openHelper.writableDatabase.execSQL(
                "DELETE FROM $table WHERE profile=?",
                arrayOf(profile.id),
            )
            dao.deleteProfileRecord(profile.id)
            preferences.savePlayerEngine(previousEngine)
            runtime.connection.selectProfile(previous.profile)
            runtime.connection.setMode(previous.mode)
        }
    }

    private suspend fun awaitState(
        stage: String,
        model: com.zenstream.zenstreammobile.ui.PlaybackViewModel,
        predicate: (com.zenstream.zenstreammobile.ui.PlaybackUiState) -> Boolean,
    ): com.zenstream.zenstreammobile.ui.PlaybackUiState =
        withTimeoutOrNull(20000) { model.uiState.first(predicate) }
            ?: throw AssertionError("Timed out at $stage: ${model.uiState.value}")

    private suspend fun assertRendered(
        activity: ActivityScenario<PlaybackActivity>,
        engine: PlayerEngine,
        stage: String,
    ) {
        withTimeout(10000) {
            while (true) {
                var surface: SurfaceView? = null
                activity.onActivity { screen -> surface = surface(screen.window.decorView) }
                val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
                val latch = CountDownLatch(1)
                var result = -1
                val selected = surface
                if (selected != null)
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        PixelCopy.request(
                            selected,
                            bitmap,
                            {
                                result = it
                                latch.countDown()
                            },
                            Handler(Looper.getMainLooper()),
                        )
                    }
                else latch.countDown()
                latch.await(3, TimeUnit.SECONDS)
                val pixels = IntArray(320 * 180)
                bitmap.getPixels(pixels, 0, 320, 0, 0, 320, 180)
                val colored = pixels.count {
                    maxOf(Color.red(it), Color.green(it), Color.blue(it)) -
                        minOf(Color.red(it), Color.green(it), Color.blue(it)) > 40
                }
                if (result == PixelCopy.SUCCESS && colored > 5000) {
                    val file =
                        File(
                            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                            "offline-video-${engine.name.lowercase()}-$stage.png",
                        )
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    return@withTimeout
                }
                bitmap.recycle()
                delay(100)
            }
        }
    }

    private fun surface(view: View): SurfaceView? =
        if (view is SurfaceView) view
        else
            (view as? ViewGroup)?.let { group ->
                (0 until group.childCount).firstNotNullOfOrNull { surface(group.getChildAt(it)) }
            }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

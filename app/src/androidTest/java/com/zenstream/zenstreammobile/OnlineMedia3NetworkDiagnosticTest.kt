package com.zenstream.zenstreammobile

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.data.INSTRUMENTATION_SESSION_DATA_STORE_NAME
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.offline.ConnectionMode
import com.zenstream.zenstreammobile.offline.OfflineNetworkPolicy
import com.zenstream.zenstreammobile.offline.OfflineRuntime
import com.zenstream.zenstreammobile.ui.player.Media3FailureKind
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local diagnostic: a reachable server rejects only subsequent media reads with HTTP 401. */
@RunWith(AndroidJUnit4::class)
class OnlineMedia3NetworkDiagnosticTest {
    @Test
    fun onlineStreamWithRejectedLeaseShowsNetworkErrorAndCanRenegotiate() =
        runBlocking<Unit> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val store =
                SessionStore(context, dataStoreName = INSTRUMENTATION_SESSION_DATA_STORE_NAME)
            val runtime = OfflineRuntime.get(context)
            val previousMode = runtime.connection.state.first { it.initialized }.mode
            val video =
                instrumentation.context.assets.open("offline/video.mp4").use { it.readBytes() }
            val reject = AtomicBoolean(false)
            val rejectedReads = AtomicInteger()
            val negotiations = AtomicInteger()
            val episode =
                """{"id":"network-episode","type":"episode","libraryId":"qa-library","durationSeconds":12,"metadata":{"title":"Online HTTP 401 diagnostic"}}"""
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val path = request.requestUrl!!.encodedPath
                            val json =
                                when {
                                    path == "/qa.mp4" -> {
                                        if (reject.get()) {
                                            rejectedReads.incrementAndGet()
                                            Log.i(
                                                "OnlineMedia3QA",
                                                "media request rejected HTTP 401",
                                            )
                                            return MockResponse()
                                                .setResponseCode(401)
                                                .setBody(
                                                    "Invalid or expired playback access lease."
                                                )
                                        }
                                        val start =
                                            request
                                                .getHeader("Range")
                                                ?.substringAfter("bytes=")
                                                ?.substringBefore('-')
                                                ?.toIntOrNull() ?: 0
                                        val response =
                                            MockResponse()
                                                .setHeader("Content-Type", "video/mp4")
                                                .setHeader("Accept-Ranges", "bytes")
                                                .setBody(
                                                    Buffer().write(video, start, video.size - start)
                                                )
                                                .throttleBody(32_768, 1, TimeUnit.SECONDS)
                                        if (start > 0)
                                            response
                                                .setResponseCode(206)
                                                .setHeader(
                                                    "Content-Range",
                                                    "bytes $start-${video.lastIndex}/${video.size}",
                                                )
                                        return response
                                    }
                                    path == "/api/catalog/items/network-episode" -> episode
                                    path.endsWith("/negotiate") -> {
                                        negotiations.incrementAndGet()
                                        """{"mode":"direct","sessionState":"ready","url":"/qa.mp4","durationSeconds":12,"source":{"id":"qa-source","container":"mp4","streams":[]}}"""
                                    }
                                    path == "/api/preferences/watch-history" ->
                                        """{"enabled":false}"""
                                    path.endsWith("/segments") -> """{"segments":[]}"""
                                    path.endsWith("/trickplay") ->
                                        return MockResponse().setResponseCode(404)
                                    path == "/api/catalog/items" -> """{"items":[]}"""
                                    else -> "{}"
                                }
                            return MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody(json)
                        }
                    }
                server.start()
                val serverUrl = server.url("/").toString().trimEnd('/')
                store.clearAll()
                store.saveServerConfig(serverUrl)
                store.saveSession(AuthSession(serverUrl, "qa-token", "qa-user", "QA"))
                store.savePlayerEngine(PlayerEngine.MEDIA3)
                store.saveAutomaticPictureInPicture(false)
                runtime.connection.setMode(ConnectionMode.AUTOMATIC)
                await("network policy allows streaming", 5_000) { OfflineNetworkPolicy.allowed }
                try {
                    val intent =
                        Intent(context, PlaybackActivity::class.java).apply {
                            putExtra(PlaybackActivityContract.EXTRA_ITEM_ID, "network-episode")
                            putExtra(
                                PlaybackActivityContract.EXTRA_ITEM_NAME,
                                "Online HTTP 401 diagnostic",
                            )
                            putExtra(
                                PlaybackActivityContract.EXTRA_SESSION_DATA_STORE,
                                INSTRUMENTATION_SESSION_DATA_STORE_NAME,
                            )
                        }
                    ActivityScenario.launch<PlaybackActivity>(intent).use { scenario ->
                        await("online video renders", 45_000) {
                            VideoPlaybackSessions.active
                                ?.playbackModel
                                ?.uiState
                                ?.value
                                ?.engine
                                ?.let { it.ready && it.positionSeconds > .3 } == true
                        }
                        val model = requireNotNull(VideoPlaybackSessions.active?.playbackModel)
                        assertFalse(model.uiState.value.local)
                        assertTrue(model.uiState.value.canStream)
                        reject.set(true)
                        instrumentation.runOnMainSync { model.seekTo(10.0) }
                        await("HTTP 401 appears as NETWORK", 25_000) {
                            model.uiState.value.error != null
                        }
                        assertTrue(rejectedReads.get() > 0)
                        assertEquals(
                            "Media3 could not play the video track (NETWORK).",
                            model.uiState.value.error,
                        )
                        assertEquals(
                            Media3FailureKind.NETWORK,
                            model.uiState.value.engine.media3?.failureKind,
                        )
                        assertFalse(model.uiState.value.local)
                        assertTrue(OfflineNetworkPolicy.allowed)
                        assertTrue(model.uiState.value.canStream)
                        assertEquals(
                            "No automatic renegotiation on HTTP 401",
                            1,
                            negotiations.get(),
                        )
                        // Hold the reproduced error for external UI-tree inspection and visual QA.
                        SystemClock.sleep(15_000)
                        val bitmap = instrumentation.uiAutomation.takeScreenshot()
                        File(context.getExternalFilesDir(null), "online-media3-http401.png")
                            .outputStream()
                            .use {
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        bitmap.recycle()
                        Log.i(
                            "OnlineMedia3QA",
                            "REPRODUCED local=false networkAllowed=true HTTP=401 error=${model.uiState.value.error}",
                        )
                        reject.set(false)
                        instrumentation.runOnMainSync { model.streamInstead() }
                        await("manual retry renegotiates and renders", 45_000) {
                            negotiations.get() == 2 &&
                                model.uiState.value.error == null &&
                                model.uiState.value.engine.ready
                        }
                        assertFalse(model.uiState.value.local)
                        Log.i(
                            "OnlineMedia3QA",
                            "PASS manual retry negotiates another online stream",
                        )
                    }
                } finally {
                    store.clearAll()
                    runtime.connection.setMode(previousMode)
                }
            }
        }

    private fun await(description: String, timeout: Long, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail(
            "Timed out: $description; state=${VideoPlaybackSessions.active?.playbackModel?.uiState?.value}"
        )
    }
}

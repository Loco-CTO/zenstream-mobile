package com.zenstream.zenstreammobile

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
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
import com.zenstream.zenstreammobile.ui.PlaybackViewModel
import com.zenstream.zenstreammobile.ui.player.Media3FailureKind
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackAccessRecoveryTest {
    @Test fun expiredMediaAccessAutomaticallyRecoversPositionAndSelections() = exercise()

    @Test fun forbiddenMediaAccessAutomaticallyRecovers() = exercise(status = 403)

    @Test fun pausedPlaybackStaysPausedAfterAutomaticRecovery() = exercise(paused = true)

    @Test
    fun foregroundReturnReplacesAnExpiredLeaseWithoutPressingPlay() = exercise(background = true)

    @Test fun persistentAccessRejectionStopsAfterOneAutomaticAttempt() = exercise(persistent = true)

    private fun exercise(
        status: Int = 401,
        paused: Boolean = false,
        background: Boolean = false,
        persistent: Boolean = false,
    ) =
        runBlocking<Unit> {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val store =
                SessionStore(context, dataStoreName = INSTRUMENTATION_SESSION_DATA_STORE_NAME)
            val runtime = OfflineRuntime.get(context)
            val previousMode = runtime.connection.state.first { it.initialized }.mode
            val video =
                instrumentation.context.assets.open("offline/video.mp4").use { it.readBytes() }
            val negotiations = CopyOnWriteArrayList<JSONObject>()
            val expiredVersion = AtomicInteger(0)
            val rejectedMedia = AtomicInteger()
            val rejectedRenewals = AtomicInteger()
            val episode =
                """{"id":"access-episode","type":"episode","libraryId":"qa-library","durationSeconds":12,"metadata":{"title":"Automatic stream recovery"}}"""
            MockWebServer().use { server ->
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val path = request.requestUrl!!.encodedPath
                            val json =
                                when {
                                    path == "/qa.mp4" -> {
                                        val version =
                                            request.requestUrl!!.queryParameter("lease")!!.toInt()
                                        if (
                                            expiredVersion.get() > 0 &&
                                                (persistent || version <= expiredVersion.get())
                                        ) {
                                            rejectedMedia.incrementAndGet()
                                            return rejected(status)
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
                                        if (expiredVersion.get() == 0)
                                            response.throttleBody(32_768, 1, TimeUnit.SECONDS)
                                        if (start > 0)
                                            response
                                                .setResponseCode(206)
                                                .setHeader(
                                                    "Content-Range",
                                                    "bytes $start-${video.lastIndex}/${video.size}",
                                                )
                                        return response
                                    }
                                    path == "/api/catalog/items/access-episode" -> episode
                                    path == "/api/auth/bootstrap" || path == "/api/auth/me" ->
                                        """{"user":{"id":"qa-user","username":"QA"}}"""
                                    path.endsWith("/negotiate") -> {
                                        negotiations += JSONObject(request.body.readUtf8())
                                        val version = negotiations.size
                                        """{"mode":"direct","sessionState":"ready","url":"/qa.mp4?lease=$version","durationSeconds":12,"accessExpiresIn":900,"playbackAccessMode":"lease-v1","playbackLeaseToken":"pl1_fixture$version","source":{"id":"qa-source","container":"mp4","streams":[{"index":0,"codec_type":"video","codec_name":"h264"},{"index":1,"codec_type":"audio","codec_name":"aac","isDefault":true}]}}"""
                                    }
                                    path.endsWith("/access") -> {
                                        if (expiredVersion.get() > 0) {
                                            rejectedRenewals.incrementAndGet()
                                            return rejected(401)
                                        }
                                        """{"playbackAccessMode":"lease-v1","expiresIn":900}"""
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
                await("network enabled") { OfflineNetworkPolicy.allowed }
                try {
                    val intent =
                        Intent(context, PlaybackActivity::class.java).apply {
                            putExtra(PlaybackActivityContract.EXTRA_ITEM_ID, "access-episode")
                            putExtra(
                                PlaybackActivityContract.EXTRA_ITEM_NAME,
                                "Automatic stream recovery",
                            )
                            putExtra(PlaybackActivityContract.EXTRA_AUDIO_STREAM_ID, 1)
                            putExtra(
                                PlaybackActivityContract.EXTRA_SESSION_DATA_STORE,
                                INSTRUMENTATION_SESSION_DATA_STORE_NAME,
                            )
                        }
                    ActivityScenario.launch<PlaybackActivity>(intent).use { scenario ->
                        await("initial video renders") {
                            VideoPlaybackSessions.active
                                ?.playbackModel
                                ?.uiState
                                ?.value
                                ?.engine
                                ?.let { it.ready && it.positionSeconds > .3 } == true
                        }
                        val model = requireNotNull(VideoPlaybackSessions.active?.playbackModel)
                        if (paused) {
                            instrumentation.runOnMainSync { model.togglePlay() }
                            await("paused", model) { !model.uiState.value.engine.isPlaying }
                        }
                        val originalNegotiations = negotiations.size
                        val position = model.uiState.value.engine.positionSeconds
                        expiredVersion.set(originalNegotiations)
                        if (background) {
                            scenario.moveToState(Lifecycle.State.CREATED)
                            scenario.moveToState(Lifecycle.State.RESUMED)
                        } else {
                            instrumentation.runOnMainSync { model.seekTo(8.0) }
                        }
                        if (persistent) {
                            await("persistent rejection is terminal", model) {
                                negotiations.size == originalNegotiations + 1 &&
                                    !model.uiState.value.loading &&
                                    model.uiState.value.engine.media3?.httpStatusCode == status
                            }
                            SystemClock.sleep(1_000)
                            assertEquals(originalNegotiations + 1, negotiations.size)
                            assertEquals(
                                Media3FailureKind.AUTHENTICATION,
                                model.uiState.value.engine.media3?.failureKind,
                            )
                            assertEquals(
                                "The server rejected playback access.",
                                model.uiState.value.error,
                            )
                            screenshot("persistent-access-rejection.png")
                        } else {
                            await("automatic recovery renders", model) {
                                negotiations.size == originalNegotiations + 1 &&
                                    model.uiState.value.engine.ready &&
                                    model.uiState.value.engine.isPlaying ==
                                        (!paused && !background) &&
                                    model.uiState.value.engine.positionSeconds >=
                                        (if (background) position - .3 else 7.8) &&
                                    model.uiState.value.error == null
                            }
                            val state = model.uiState.value
                            assertFalse(state.local)
                            assertTrue(OfflineNetworkPolicy.allowed)
                            assertEquals(!paused && !background, state.engine.media3?.playWhenReady)
                            assertEquals(!paused && !background, state.engine.isPlaying)
                            assertTrue(
                                state.engine.positionSeconds >=
                                    (if (background) position - .3 else 7.8)
                            )
                            assertEquals(0, state.selectedQuality)
                            assertEquals(1, state.selectedAudio)
                            assertEquals(0, state.engine.media3?.videoSoftwareRetries)
                            val body = negotiations.last()
                            assertEquals("qa-source", body.getString("sourceId"))
                            assertEquals("direct", body.getString("requestedMode"))
                            assertEquals(1, body.getInt("audioStreamId"))
                            assertFalse(body.getBoolean("forceTranscoding"))
                            assertFalse(body.has("maxStreamingBitrate"))
                            if (background) {
                                assertTrue(rejectedRenewals.get() > 0)
                                assertEquals(0, rejectedMedia.get())
                            } else {
                                assertTrue(rejectedMedia.get() > 0)
                            }
                            screenshot("automatic-access-recovery-$status-$paused-$background.png")
                            if (paused) {
                                // A successfully restored paused frame must also allow a later
                                // lease expiry to recover without first playing the video.
                                expiredVersion.set(negotiations.size)
                                scenario.moveToState(Lifecycle.State.CREATED)
                                scenario.moveToState(Lifecycle.State.RESUMED)
                                await("second paused lease recovers", model) {
                                    negotiations.size == originalNegotiations + 2 &&
                                        model.uiState.value.engine.ready &&
                                        model.uiState.value.engine.positionSeconds >= 7.8 &&
                                        model.uiState.value.error == null
                                }
                                assertFalse(model.uiState.value.engine.isPlaying)
                                assertEquals(
                                    false,
                                    model.uiState.value.engine.media3?.playWhenReady,
                                )
                            }
                        }
                    }
                } finally {
                    store.clearAll()
                    runtime.connection.setMode(previousMode)
                }
            }
        }

    private fun rejected(status: Int) =
        MockResponse()
            .setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"detail":"Invalid or expired playback access lease."}""")

    private fun await(
        description: String,
        model: PlaybackViewModel? = null,
        condition: () -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail("Timed out: $description; state=${model?.uiState?.value}")
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}

package com.zenstream.zenstreammobile.offline

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.PlaybackActivity
import com.zenstream.zenstreammobile.PlaybackActivityContract
import com.zenstream.zenstreammobile.VideoPlaybackSessions
import com.zenstream.zenstreammobile.data.CatalogApi
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.INSTRUMENTATION_SESSION_DATA_STORE_NAME
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.PlayerEngine
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses the real Room outbox and HTTP client, with no signed-in account or background upload. */
class WatchedStatePersistenceTest {
    @Test
    fun manualWatchAndUnwatchAreVisibleToAFreshClientBeforeReturning() = exercise { fixture ->
        fixture.repository.setPlayed(fixture.session, "movie", true)
        assertTrue("A fresh client must see the saved watched state", fixture.freshPlayed("movie"))
        fixture.repository.setPlayed(fixture.session, "movie", false)
        assertFalse(fixture.freshPlayed("movie"))
        assertTrue(fixture.runtime.database.offline().operations(fixture.profile.id, 100).isEmpty())
    }

    @Test
    fun seriesWatchUsesCatalogFallbackWhenAccountSyncRejectsContainers() = exercise { fixture ->
        fixture.repository.setPlayed(fixture.session, "series", true)
        assertTrue("Series watched state must reach the catalog API", fixture.freshPlayed("series"))
        fixture.repository.setPlayed(fixture.session, "series", false)
        assertFalse(fixture.freshPlayed("series"))
    }

    @Test
    fun completionIsSavedBeforeReturningAndCannotBeLostToAQueuedCheckpoint() = exercise { fixture ->
        fixture.runtime.submitOnlineMutation(
            fixture.session,
            "movie",
            "checkpoint",
            JSONObject().put("positionSeconds", 20).put("durationSeconds", 100),
        )
        fixture.runtime.submitOnlineMutation(fixture.session, "movie", "complete")
        assertTrue("Completion must be visible outside this device", fixture.freshPlayed("movie"))
        assertTrue(fixture.runtime.database.offline().operations(fixture.profile.id, 100).isEmpty())
    }

    @Test
    fun retryingTheSameWatchChangeUploadsThePendingOperation() = exercise { fixture ->
        fixture.failSync.set(true)
        fixture.repository.setPlayed(fixture.session, "movie", true)
        assertFalse(fixture.freshPlayed("movie"))
        assertEquals(1, fixture.runtime.database.offline().operations(fixture.profile.id, 100).size)
        fixture.failSync.set(false)
        fixture.repository.setPlayed(fixture.session, "movie", true)
        assertTrue(fixture.freshPlayed("movie"))
        assertTrue(fixture.runtime.database.offline().operations(fixture.profile.id, 100).isEmpty())
    }

    @Test
    fun offlineWatchChangeRemainsDurableUntilReconnection() = exercise { fixture ->
        fixture.runtime.connection.setMode(ConnectionMode.OFFLINE)
        fixture.repository.setPlayed(fixture.session, "movie", true)
        assertTrue(
            fixture.runtime.database.offline().playbackState(fixture.profile.id, "movie")!!.played
        )
        assertEquals(1, fixture.runtime.database.offline().operations(fixture.profile.id, 100).size)
        fixture.runtime.connection.setMode(ConnectionMode.AUTOMATIC)
        fixture.runtime.connection.state.first { it.mode == ConnectionMode.AUTOMATIC }
        OfflineNetworkPolicy.publish(true)
        assertFalse(fixture.freshPlayed("movie"))
        fixture.runtime.accountSync.reconcile(fixture.profile.id, fixture.session)
        assertTrue(fixture.freshPlayed("movie"))
    }

    @Test
    fun automaticCompletionHonorsTheLatestServerPrivacySetting() = exercise { fixture ->
        fixture.historyEnabled.set(false)
        fixture.repository.completePlayback(fixture.session, "movie", 100.0)
        assertFalse(fixture.freshPlayed("movie"))
        assertTrue(fixture.runtime.database.offline().operations(fixture.profile.id, 100).isEmpty())
    }

    @Test
    fun streamedVideoCompletionSurvivesPlayerCloseAndAFreshRead() = exercise { fixture ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val storeName = INSTRUMENTATION_SESSION_DATA_STORE_NAME
        val store = SessionStore(context, dataStoreName = storeName)
        store.clearAll()
        store.saveServerConfig(fixture.session.serverUrl)
        store.saveSession(fixture.session)
        store.savePlayerEngine(PlayerEngine.MEDIA3)
        store.saveAutomaticPictureInPicture(false)
        try {
            val intent =
                Intent(context, PlaybackActivity::class.java).apply {
                    putExtra(PlaybackActivityContract.EXTRA_ITEM_ID, "movie")
                    putExtra(PlaybackActivityContract.EXTRA_ITEM_NAME, "Watched-state fixture")
                    putExtra(PlaybackActivityContract.EXTRA_AUDIO_STREAM_ID, 1)
                    putExtra(PlaybackActivityContract.EXTRA_SESSION_DATA_STORE, storeName)
                }
            ActivityScenario.launch<PlaybackActivity>(intent).use {
                withTimeout(30_000) {
                    while (
                        VideoPlaybackSessions.active?.playbackModel?.uiState?.value?.engine?.let {
                            it.ready && it.positionSeconds > .2
                        } != true
                    ) delay(100)
                }
                val model = requireNotNull(VideoPlaybackSessions.active?.playbackModel)
                assertFalse(model.uiState.value.local)
                instrumentation.runOnMainSync { model.seekTo(11.5) }
                val closed =
                    withTimeoutOrNull(30_000) {
                        while (!model.uiState.value.closeRequested) delay(100)
                        true
                    }
                assertTrue(
                    "Playback did not close after ending: ${model.uiState.value}",
                    closed == true,
                )
                assertTrue(
                    "Ended streaming video must be saved before closing",
                    fixture.freshPlayed("movie"),
                )
                // Allow the player's final progress flush, then explicitly drain any queued work.
                delay(300)
                assertTrue(
                    "Closing must not queue another checkpoint after completion",
                    fixture.runtime.database.offline().operations(fixture.profile.id, 100).none {
                        it.kind == "checkpoint"
                    },
                )
                fixture.runtime.accountSync.reconcile(fixture.profile.id, fixture.session)
                assertTrue(
                    "A terminal checkpoint must not undo completion",
                    fixture.freshPlayed("movie"),
                )
            }
        } finally {
            instrumentation.runOnMainSync { VideoPlaybackSessions.close() }
            store.clearAll()
        }
    }

    private fun exercise(block: suspend (Fixture) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = OfflineRuntime.get(context)
        assumeTrue(
            "Use an isolated test install without an account",
            runtime.sessionStore.session.first() == null,
        )
        val previous = runtime.connection.state.first { it.initialized }
        MockWebServer().use { server ->
            val states = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
            val failSync = AtomicBoolean(false)
            val historyEnabled = AtomicBoolean(true)
            val video =
                InstrumentationRegistry.getInstrumentation()
                    .context
                    .assets
                    .open("offline/video.mp4")
                    .use { it.readBytes() }
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.requestUrl!!.encodedPath
                        if (path == "/qa.mp4") {
                            val start =
                                request
                                    .getHeader("Range")
                                    ?.substringAfter("bytes=")
                                    ?.substringBefore('-')
                                    ?.toIntOrNull() ?: 0
                            return MockResponse()
                                .setHeader("Content-Type", "video/mp4")
                                .setHeader("Accept-Ranges", "bytes")
                                .setBody(Buffer().write(video, start, video.size - start))
                                .apply {
                                    if (start > 0)
                                        setResponseCode(206)
                                            .setHeader(
                                                "Content-Range",
                                                "bytes $start-${video.lastIndex}/${video.size}",
                                            )
                                }
                        }
                        val result =
                            when {
                                path == "/api/offline/reconcile" ->
                                    envelope()
                                        .put("serverInstanceId", "watch-test")
                                        .put("watchHistoryEnabled", historyEnabled.get())
                                path == "/api/account/sync" -> {
                                    if (failSync.get())
                                        return MockResponse().setResponseCode(503).setBody("{}")
                                    val operations =
                                        JSONObject(request.body.readUtf8())
                                            .getJSONArray("operations")
                                    val acknowledgements = JSONArray()
                                    repeat(operations.length()) { index ->
                                        val operation = operations.getJSONObject(index)
                                        val target = operation.getString("targetId")
                                        val kind = operation.getString("kind")
                                        val privacyBlocked =
                                            !historyEnabled.get() &&
                                                kind in setOf("checkpoint", "complete")
                                        if (target != "series" && !privacyBlocked) {
                                            val payload = operation.getJSONObject("payload")
                                            val duration = payload.optDouble("durationSeconds", 0.0)
                                            states[target] =
                                                kind == "watched" ||
                                                    kind == "complete" ||
                                                    kind == "checkpoint" &&
                                                        duration > 0 &&
                                                        payload.optDouble("positionSeconds", 0.0) /
                                                            duration >= .9
                                        }
                                        acknowledgements.put(
                                            JSONObject()
                                                .put("id", operation.getString("id"))
                                                .put("sequence", operation.getLong("sequence"))
                                                .put("targetId", target)
                                                .put(
                                                    "outcome",
                                                    if (privacyBlocked) "privacy_blocked"
                                                    else if (target == "series") "rejected"
                                                    else "applied",
                                                )
                                                .put(
                                                    "state",
                                                    JSONObject()
                                                        .put("played", states[target] ?: false),
                                                )
                                                .put(
                                                    "revision",
                                                    JSONObject()
                                                        .put(
                                                            "revision",
                                                            operation.getLong("sequence"),
                                                        ),
                                                )
                                        )
                                    }
                                    envelope().put("acknowledgements", acknowledgements)
                                }
                                path.endsWith("/negotiate") ->
                                    JSONObject(
                                        """{"mode":"direct","sessionState":"ready","url":"/qa.mp4","durationSeconds":12,"source":{"id":"qa-source","container":"mp4","streams":[{"index":0,"codec_type":"video","codec_name":"h264"},{"index":1,"codec_type":"audio","codec_name":"aac","isDefault":true}]}}"""
                                    )
                                path == "/api/preferences/watch-history" ->
                                    JSONObject().put("enabled", historyEnabled.get())
                                path.endsWith("/segments") ->
                                    JSONObject().put("segments", JSONArray())
                                path.endsWith("/trickplay") ->
                                    return MockResponse().setResponseCode(404)
                                path == "/api/catalog/items" ->
                                    JSONObject().put("items", JSONArray())
                                path.endsWith("/state") -> {
                                    val target =
                                        path.substringBeforeLast('/').substringAfterLast('/')
                                    states[target] =
                                        JSONObject(request.body.readUtf8()).getBoolean("played")
                                    JSONObject()
                                }
                                path in
                                    setOf(
                                        "/api/catalog/items/movie",
                                        "/api/catalog/items/series",
                                    ) -> {
                                    val target = path.substringAfterLast('/')
                                    JSONObject()
                                        .put("id", target)
                                        .put("type", if (target == "series") "series" else "movie")
                                        .put("metadata", JSONObject().put("title", target))
                                        .put(
                                            "userState",
                                            JSONObject().put("played", states[target] ?: false),
                                        )
                                }
                                else -> JSONObject()
                            }
                        return MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(result.toString())
                            .apply {
                                // Exercise completion across subsequent engine-state emissions.
                                if (path == "/api/account/sync")
                                    setBodyDelay(350, TimeUnit.MILLISECONDS)
                            }
                    }
                }
            server.start()
            val session =
                AuthSession(
                    server.url("/").toString().trimEnd('/'),
                    "test-token",
                    "watch-test",
                    "QA",
                )
            val profile =
                OfflineProfile(
                    UUID.randomUUID().toString(),
                    "watch-test",
                    session.userId,
                    session.serverUrl,
                    "QA",
                )
            runtime.database.offline().putProfile(profile)
            runtime.connection.setMode(ConnectionMode.AUTOMATIC)
            runtime.connection.state.first { it.mode == ConnectionMode.AUTOMATIC }
            OfflineNetworkPolicy.publish(true)
            runtime.connection.selectProfile(profile.id)
            runtime.connection.state.first { it.profile == profile.id }
            try {
                block(
                    Fixture(
                        runtime,
                        session,
                        profile,
                        CatalogRepository(CatalogApi(), runtime.sessionStore, offline = runtime),
                        failSync,
                        historyEnabled,
                    )
                )
            } finally {
                runtime.connection.selectProfile(previous.profile)
                runtime.connection.setMode(previous.mode)
                runtime.storage.removeProfile(profile.id)
            }
        }
    }

    private fun envelope() =
        JSONObject()
            .put("serverTime", Instant.now().toString())
            .put("historyEpoch", 0)
            .put("privacyEpoch", 0)

    private data class Fixture(
        val runtime: OfflineRuntime,
        val session: AuthSession,
        val profile: OfflineProfile,
        val repository: CatalogRepository,
        val failSync: AtomicBoolean,
        val historyEnabled: AtomicBoolean,
    ) {
        suspend fun freshPlayed(target: String): Boolean =
            CatalogApi().catalogItem(session, target).played
    }
}

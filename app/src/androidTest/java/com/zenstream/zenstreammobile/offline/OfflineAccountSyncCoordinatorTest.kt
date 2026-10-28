package com.zenstream.zenstreammobile.offline

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineAccountSyncCoordinatorTest {
    @Test
    fun refreshesPrivacyBeforeOrderedUploadAndAppliesCanonicalState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            val dao = database.offline()
            dao.putProfile(profile())
            val requests = mutableListOf<Pair<String, JSONObject?>>()
            val coordinator =
                OfflineAccountSyncCoordinator(
                    context,
                    database,
                    request = { _, path, _, body ->
                        requests += path to body?.let { JSONObject(it.toString()) }
                        when (path) {
                            "/api/offline/reconcile" -> barrier()
                            "/api/account/sync" -> syncResponse(requireNotNull(body))
                            else -> error("Unexpected request: $path")
                        }
                    },
                    deviceId = { "stable-installation-device" },
                )
            coordinator.refreshBarrier("profile", session())

            val writer = OfflinePlaybackStateRepository(context, database)
            writer.checkpoint("profile", "movie-1", 25.0, 100.0, historyEnabled = true)
            writer.setAccountFlag("profile", "movie-1", "favorite", true)

            val result = coordinator.reconcile("profile", session())

            assertEquals(2, result.acknowledgedCount)
            assertEquals(listOf("applied", "applied"), result.outcomes.values.toList())
            val syncBody = requests.last { it.first == "/api/account/sync" }.second!!
            assertEquals("server-instance", syncBody.getString("serverInstanceId"))
            assertEquals("stable-installation-device", syncBody.getString("deviceId"))
            val operations = syncBody.getJSONArray("operations")
            assertEquals(
                listOf(1L, 2L),
                (0 until operations.length()).map {
                    operations.getJSONObject(it).getLong("sequence")
                },
            )
            assertEquals(4L, operations.getJSONObject(0).getLong("historyEpoch"))
            assertEquals(9L, operations.getJSONObject(0).getLong("privacyEpoch"))

            val updatedProfile = dao.profile("profile")!!
            assertEquals(2L, updatedProfile.acknowledgedSequence)
            assertEquals(4L, updatedProfile.historyEpoch)
            assertEquals(9L, updatedProfile.privacyEpoch)
            assertTrue(updatedProfile.anchorServerMs > 0L)
            assertEquals(0, dao.operations("profile", 10).size)
            val userState = dao.state("profile", "movie-1")!!
            assertTrue(JSONObject(userState.canonical).getBoolean("favorite"))
            assertEquals(0, JSONObject(userState.overlay).length())
            val playback = dao.playbackState("profile", "movie-1")!!
            assertEquals(25.0, playback.positionSeconds, 0.01)
            assertEquals(100.0, playback.durationSeconds, 0.01)
        } finally {
            database.close()
        }
    }

    @Test
    fun retriesLostAcknowledgementWithTheExactFrozenOperation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            val dao = database.offline()
            dao.putProfile(profile())
            OfflinePlaybackStateRepository(context, database)
                .setAccountFlag("profile", "movie-1", "favorite", true)
            val requestBodies = mutableListOf<String>()
            var syncCalls = 0
            val coordinator =
                OfflineAccountSyncCoordinator(
                    context,
                    database,
                    request = { _, path, _, body ->
                        when (path) {
                            "/api/offline/reconcile" -> barrier()
                            "/api/account/sync" -> {
                                val value = requireNotNull(body)
                                requestBodies += value.toString()
                                syncCalls++
                                if (syncCalls == 1) throw IOException("response lost after commit")
                                syncResponse(value, outcome = "duplicate")
                            }
                            else -> error("Unexpected request: $path")
                        }
                    },
                    deviceId = { "stable-installation-device" },
                )

            assertTrue(runCatching { coordinator.reconcile("profile", session()) }.isFailure)
            assertTrue(dao.operations("profile", 10).single().inFlight)
            val replay = coordinator.reconcile("profile", session())

            assertEquals(2, requestBodies.size)
            assertEquals(
                "retry must preserve id, sequence, payload, and event time",
                requestBodies[0],
                requestBodies[1],
            )
            assertEquals("duplicate", replay.outcomes.values.single())
            assertTrue(dao.operations("profile", 10).isEmpty())
            assertEquals(1L, dao.profile("profile")!!.acknowledgedSequence)
        } finally {
            database.close()
        }
    }

    @Test
    fun nextOperationUsesTheCanonicalFieldRevisionWhenItsClockIsUntrusted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            val dao = database.offline()
            dao.putProfile(profile())
            val writer = OfflinePlaybackStateRepository(context, database)
            writer.setAccountFlag("profile", "movie-1", "favorite", true)
            val coordinator =
                OfflineAccountSyncCoordinator(
                    context,
                    database,
                    request = { _, path, _, body ->
                        when (path) {
                            "/api/offline/reconcile" -> barrier()
                            "/api/account/sync" -> syncResponse(requireNotNull(body))
                            else -> error("Unexpected request: $path")
                        }
                    },
                    deviceId = { "stable-installation-device" },
                )

            coordinator.reconcile("profile", session())
            dao.profile("profile")!!.also {
                dao.putProfile(it.copy(anchorServerMs = 0, anchorElapsedMs = 0, anchorBoot = -1))
            }
            writer.setAccountFlag("profile", "movie-1", "favorite", false)

            val pending = dao.operations("profile", 10).single()
            assertFalse(pending.clockTrusted)
            assertEquals(1L, pending.baseRevision)
        } finally {
            database.close()
        }
    }

    @Test
    fun canonicalAcknowledgementDoesNotReplaceANewerPendingOverlay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            val dao = database.offline()
            dao.putProfile(profile())
            val writer = OfflinePlaybackStateRepository(context, database)
            writer.setAccountFlag("profile", "movie-1", "favorite", true)
            repeat(99) { index ->
                writer.setAccountFlag("profile", "other-$index", "favorite", true)
            }
            writer.setAccountFlag("profile", "movie-1", "favorite", false)
            val coordinator =
                OfflineAccountSyncCoordinator(
                    context,
                    database,
                    request = { _, path, _, body ->
                        when (path) {
                            "/api/offline/reconcile" -> barrier()
                            "/api/account/sync" -> {
                                if (
                                    requireNotNull(body)
                                        .getJSONArray("operations")
                                        .getJSONObject(0)
                                        .getLong("sequence") > 100L
                                )
                                    throw IOException("stop after first batch")
                                syncResponse(body)
                            }
                            else -> error("Unexpected request: $path")
                        }
                    },
                    deviceId = { "stable-installation-device" },
                )

            assertTrue(runCatching { coordinator.reconcile("profile", session()) }.isFailure)

            val state = dao.state("profile", "movie-1")!!
            assertTrue(JSONObject(state.canonical).getBoolean("favorite"))
            assertFalse(JSONObject(state.overlay).getBoolean("favorite"))
            assertEquals(1, dao.operations("profile", 10).size)
            assertEquals(101L, dao.operations("profile", 10).single().sequence)
        } finally {
            database.close()
        }
    }

    @Test
    fun privacyBlockedAutomaticHistoryKeepsResumeAsLocalOnly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            val dao = database.offline()
            dao.putProfile(profile())
            OfflinePlaybackStateRepository(context, database)
                .checkpoint("profile", "movie-1", 38.0, 120.0, historyEnabled = true)
            val coordinator =
                OfflineAccountSyncCoordinator(
                    context,
                    database,
                    request = { _, path, _, body ->
                        when (path) {
                            "/api/offline/reconcile" -> barrier(historyEnabled = false)
                            "/api/account/sync" ->
                                syncResponse(requireNotNull(body), outcome = "privacy_blocked")
                            else -> error("Unexpected request: $path")
                        }
                    },
                    deviceId = { "stable-installation-device" },
                )

            val pass = coordinator.reconcile("profile", session())

            assertEquals("privacy_blocked", pass.outcomes.values.single())
            val state = dao.state("profile", "movie-1")!!
            val localOnlyResume = requireNotNull(state.localOnlyResume)
            assertFalse(JSONObject(state.overlay).has("positionSeconds"))
            assertEquals(
                38.0,
                JSONObject(localOnlyResume).getDouble("positionSeconds"),
                0.01,
            )
            assertEquals(
                120.0,
                JSONObject(localOnlyResume).getDouble("durationSeconds"),
                0.01,
            )
            assertFalse(dao.profile("profile")!!.historyEnabled)
            assertEquals(0, dao.operations("profile", 10).size)
        } finally {
            database.close()
        }
    }

    @Test
    fun playlistAcknowledgementAppliesCanonicalSummaryWithoutTouchingAssets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java).build()
        try {
            val dao = database.offline()
            dao.putProfile(profile())
            val catalog =
                OfflineCatalogRepository(database) { _, _, _, _ ->
                    error("No metadata request expected")
                }
            val writer = OfflinePlaybackStateRepository(context, database)
            val playlists = OfflinePlaylistRepository(database, writer, catalog)
            val playlistId = playlists.create("profile", "On the road")
            val coordinator =
                OfflineAccountSyncCoordinator(
                    context,
                    database,
                    request = { _, path, _, body ->
                        when (path) {
                            "/api/offline/reconcile" -> barrier()
                            "/api/account/sync" -> syncResponse(requireNotNull(body))
                            else -> error("Unexpected request: $path")
                        }
                    },
                    deviceId = { "stable-installation-device" },
                )

            coordinator.reconcile("profile", session())

            val playlist = dao.playlist("profile", playlistId)!!
            assertEquals("On the road", JSONObject(playlist.metadata).getString("name"))
            assertEquals("applied", JSONObject(playlist.metadata).getString("lastSyncOutcome"))
            assertTrue(playlist.complete)
            assertEquals(0, dao.operations("profile", 10).size)
        } finally {
            database.close()
        }
    }

    private fun profile() =
        OfflineProfile("profile", "server-instance", "account", "https://example.invalid", "Local")

    private fun session() =
        com.zenstream.zenstreammobile.model.AuthSession(
            "https://example.invalid",
            "token",
            "account",
            "Local",
        )

    private fun barrier(historyEnabled: Boolean = true) =
        JSONObject()
            .put("serverInstanceId", "server-instance")
            .put("serverTime", Instant.now().toString())
            .put("historyEpoch", 4)
            .put("privacyEpoch", 9)
            .put("watchHistoryEnabled", historyEnabled)
            .put("libraryAccess", JSONObject())
            .put("objects", JSONArray())

    private fun syncResponse(
        body: JSONObject,
        outcome: String = "applied",
    ): JSONObject {
        val acknowledgements = JSONArray()
        val operations = body.getJSONArray("operations")
        repeat(operations.length()) { index ->
            val operation = operations.getJSONObject(index)
            val kind = operation.getString("kind")
            val state =
                when (kind) {
                    "checkpoint",
                    "complete",
                    "watched",
                    "unwatched" ->
                        JSONObject()
                            .put("positionSeconds", 25.0)
                            .put("durationSeconds", 100.0)
                            .put("played", kind == "watched" || kind == "complete")
                            .put("lastPlayedAt", Instant.now().toString())
                    "favorite",
                    "unfavorite" -> JSONObject().put("favorite", kind == "favorite")
                    "follow",
                    "unfollow" -> JSONObject().put("following", kind == "follow")
                    else ->
                        JSONObject()
                            .put("id", operation.getString("targetId"))
                            .put(
                                "name",
                                JSONObject(operation.getString("payload")).optString("name"),
                            )
                            .put("description", JSONObject.NULL)
                            .put("isPrivate", true)
                            .put("itemCount", 0)
                            .put("entryId", operation.getString("id"))
                }
            acknowledgements.put(
                JSONObject()
                    .put("id", operation.getString("id"))
                    .put("sequence", operation.getLong("sequence"))
                    .put("outcome", outcome)
                    .put("targetId", operation.getString("targetId"))
                    .put("state", state)
                    .put("revision", JSONObject().put("revision", operation.getLong("sequence")))
            )
        }
        return JSONObject()
            .put("serverTime", Instant.now().toString())
            .put("historyEpoch", 4)
            .put("privacyEpoch", 9)
            .put("acknowledgements", acknowledgements)
    }
}

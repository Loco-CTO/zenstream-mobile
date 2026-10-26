package com.zenstream.zenstreammobile.offline

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import com.zenstream.zenstreammobile.model.AuthSession
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Uploads the durable account outbox in device-sequence order and applies server acknowledgements.
 */
class OfflineAccountSyncCoordinator(
    context: Context,
    private val database: OfflineDatabase,
    private val request: OfflineRequest,
    private val deviceId: suspend () -> String,
) {
    private val appContext = context.applicationContext
    private val dao = database.offline()
    private val syncMutex = Mutex()

    suspend fun reconcile(
        profileId: String,
        session: AuthSession,
        refreshFirst: Boolean = true,
    ): OfflineSyncPass = syncMutex.withLock {
        var profile = requireMatchingProfile(profileId, session)
        if (refreshFirst) profile = refreshBarrier(profile, session)
        val outcomes = LinkedHashMap<String, String>()
        var processed = 0
        repeat(MAX_BATCHES_PER_PASS) {
            val batch = dao.operations(profileId, BATCH_SIZE)
            if (batch.isEmpty()) return@repeat
            requireContiguous(profile, batch)
            val through = batch.last().sequence
            database.withTransaction { dao.freezeOperations(profileId, through) }

            // Frozen rows are replayed byte-for-byte after any uncertain transport failure.
            val response =
                request(
                    session,
                    "/api/account/sync",
                    "POST",
                    syncRequest(profile, batch),
                )
            val acknowledgements = validateAcknowledgements(batch, response)
            val responseTime = serverMillis(response)
            val nowElapsed = SystemClock.elapsedRealtime()
            val boot = bootCount()
            database.withTransaction {
                batch.forEach { operation ->
                    val acknowledgement = acknowledgements.getValue(operation.id)
                    applyAcknowledgement(operation, acknowledgement, outcomes)
                }
                val latest = requireNotNull(dao.profile(profileId))
                dao.putProfile(
                    latest.copy(
                        acknowledgedSequence = through,
                        historyEpoch = response.getLong("historyEpoch"),
                        privacyEpoch = response.getLong("privacyEpoch"),
                        anchorServerMs = responseTime,
                        anchorElapsedMs = nowElapsed,
                        anchorBoot = boot,
                    )
                )
                dao.acknowledge(profileId, through)
            }
            processed += batch.size
            profile = requireNotNull(dao.profile(profileId))
        }
        OfflineSyncPass(processed, outcomes)
    }

    /** Refresh privacy generations before any automatic history operation can be uploaded. */
    suspend fun refreshBarrier(profileId: String, session: AuthSession): OfflineProfile =
        syncMutex.withLock {
            refreshBarrier(requireMatchingProfile(profileId, session), session)
        }

    private suspend fun refreshBarrier(
        profile: OfflineProfile,
        session: AuthSession,
    ): OfflineProfile {
        val body =
            JSONObject()
                .put("serverInstanceId", profile.serverInstance)
                .put("language", "en")
                .put("objects", JSONArray())
        val response = request(session, "/api/offline/reconcile", "POST", body)
        check(response.optString("serverInstanceId") == profile.serverInstance) {
            "The server installation changed; this offline profile cannot sync to it."
        }
        val serverMs = serverMillis(response)
        val elapsedMs = SystemClock.elapsedRealtime()
        val boot = bootCount()
        return database.withTransaction {
            val latest = requireMatchingProfile(profile.id, session)
            latest
                .copy(
                    historyEpoch = response.getLong("historyEpoch"),
                    privacyEpoch = response.getLong("privacyEpoch"),
                    historyEnabled = response.getBoolean("watchHistoryEnabled"),
                    anchorServerMs = serverMs,
                    anchorElapsedMs = elapsedMs,
                    anchorBoot = boot,
                )
                .also { dao.putProfile(it) }
        }
    }

    private suspend fun requireMatchingProfile(
        profileId: String,
        session: AuthSession,
    ): OfflineProfile {
        val profile = requireNotNull(dao.profile(profileId)) { "Offline profile is unavailable." }
        check(!profile.locked) { "Sign in to unlock these downloads." }
        require(profile.account == session.userId) { "Offline profile belongs to another account." }
        require(normalizeServer(profile.serverUrl) == normalizeServer(session.serverUrl)) {
            "Offline profile belongs to another server."
        }
        return profile
    }

    private suspend fun requireContiguous(
        profile: OfflineProfile,
        operations: List<OfflineOperation>,
    ) {
        require(operations.size <= BATCH_SIZE)
        var expected = profile.acknowledgedSequence + 1
        operations.forEach { operation ->
            check(operation.profile == profile.id && operation.sequence == expected) {
                "Account outbox sequence is incomplete; refusing to skip an operation."
            }
            expected++
        }
    }

    private suspend fun syncRequest(
        profile: OfflineProfile,
        operations: List<OfflineOperation>,
    ): JSONObject {
        val values = JSONArray()
        operations.forEach { operation ->
            values.put(
                JSONObject()
                    .put("id", operation.id)
                    .put("sequence", operation.sequence)
                    .put("kind", operation.kind)
                    .put("targetId", operation.target)
                    .put("activityMs", operation.activityMs)
                    .put("clockTrusted", operation.clockTrusted)
                    .put("baseRevision", operation.baseRevision)
                    .put("historyEpoch", operation.historyEpoch)
                    .put("privacyEpoch", operation.privacyEpoch)
                    .put("payload", JSONObject(operation.payload))
            )
        }
        return JSONObject()
            .put("serverInstanceId", profile.serverInstance)
            .put("deviceId", deviceId())
            .put("operations", values)
    }

    private fun validateAcknowledgements(
        operations: List<OfflineOperation>,
        response: JSONObject,
    ): Map<String, JSONObject> {
        val values =
            response.optJSONArray("acknowledgements")
                ?: error("Account sync response omitted acknowledgements.")
        require(values.length() == operations.size) {
            "Account sync response did not acknowledge the full batch."
        }
        val byId = LinkedHashMap<String, JSONObject>()
        repeat(values.length()) {
            val acknowledgement = values.getJSONObject(it)
            val id = acknowledgement.getString("id")
            require(byId.put(id, acknowledgement) == null) {
                "Account sync response repeated an acknowledgement."
            }
            require(acknowledgement.getString("outcome") in TERMINAL_OUTCOMES) {
                "Account sync response contained a non-terminal outcome."
            }
        }
        operations.forEach { operation ->
            val acknowledgement =
                requireNotNull(byId[operation.id]) {
                    "Account sync response omitted operation ${operation.id}."
                }
            require(acknowledgement.getLong("sequence") == operation.sequence)
            require(acknowledgement.getString("targetId") == operation.target)
        }
        return byId
    }

    private suspend fun applyAcknowledgement(
        operation: OfflineOperation,
        acknowledgement: JSONObject,
        outcomes: MutableMap<String, String>,
    ) {
        val outcome = acknowledgement.getString("outcome")
        outcomes[operation.id] = outcome
        if (operation.kind.startsWith("playlist_")) {
            applyPlaylistAcknowledgement(operation, acknowledgement, outcome)
        } else {
            applyStateAcknowledgement(operation, acknowledgement, outcome)
        }
    }

    private suspend fun applyStateAcknowledgement(
        operation: OfflineOperation,
        acknowledgement: JSONObject,
        outcome: String,
    ) {
        val returnedState = acknowledgement.optJSONObject("state")
        val current =
            dao.state(operation.profile, operation.target)
                ?: OfflineUserState(operation.profile, operation.target)
        val canonical = JSONObject(current.canonical)
        returnedState?.keys()?.forEach { key -> canonical.put(key, returnedState.get(key)) }

        val later =
            dao.targetOperations(operation.profile, operation.target).filter {
                it.sequence > operation.sequence
            }
        val field = stateField(operation.kind)
        val overlay = JSONObject(current.overlay)
        val overlayKeys =
            when (operation.kind) {
                "checkpoint",
                "complete",
                "watched",
                "unwatched" -> PLAYBACK_OVERLAY_KEYS
                "music_play" -> setOf("playCount", "lastPlayedAt")
                "favorite",
                "unfavorite" -> setOf("favorite")
                "follow",
                "unfollow" -> setOf("following")
                else -> emptySet()
            }
        overlayKeys.forEach { key ->
            val hasLaterValue =
                when {
                    operation.kind == "music_play" && key == "playCount" ->
                        later.any { it.kind == "music_play" }
                    operation.kind == "music_play" && key == "lastPlayedAt" ->
                        later.any { it.kind == "music_play" || it.kind in PLAYBACK_KINDS }
                    else -> later.any { stateField(it.kind) == field }
                }
            if (!hasLaterValue) overlay.remove(key)
        }

        val revision = acknowledgement.optJSONObject("revision")
        val revisions = JSONObject(current.revisions)
        if (revision != null) revisions.put(field, revision)
        val localOnlyResume =
            if (outcome == "privacy_blocked" && operation.kind in setOf("checkpoint", "complete")) {
                val local = JSONObject(current.localOnlyResume ?: "{}")
                val pendingOverlay = JSONObject(current.overlay)
                PLAYBACK_OVERLAY_KEYS.forEach { key ->
                    if (pendingOverlay.has(key)) local.put(key, pendingOverlay.get(key))
                }
                local.toString().takeUnless { local.length() == 0 }
            } else {
                current.localOnlyResume
            }
        dao.putState(
            current.copy(
                canonical = canonical.toString(),
                overlay = overlay.toString(),
                revisions = revisions.toString(),
                localOnlyResume = localOnlyResume,
            )
        )

        if (
            returnedState != null &&
                operation.kind in PLAYBACK_KINDS &&
                later.none { it.kind in PLAYBACK_KINDS }
        ) {
            val position = canonical.optDouble("positionSeconds", 0.0).finiteNonNegative()
            val duration = canonical.optDouble("durationSeconds", 0.0).finiteNonNegative()
            val previous = dao.playbackState(operation.profile, operation.target)
            dao.putPlaybackState(
                OfflinePlaybackState(
                    profile = operation.profile,
                    target = operation.target,
                    positionSeconds = position,
                    durationSeconds = duration,
                    played = canonical.optBoolean("played"),
                    activityMs =
                        parseActivityMs(canonical.optString("lastPlayedAt")).takeIf { it > 0 }
                            ?: previous?.activityMs
                            ?: operation.activityMs,
                    previousEpisodeId = previous?.previousEpisodeId,
                    nextEpisodeId = previous?.nextEpisodeId,
                )
            )
        }
    }

    private suspend fun applyPlaylistAcknowledgement(
        operation: OfflineOperation,
        acknowledgement: JSONObject,
        outcome: String,
    ) {
        val row = dao.playlist(operation.profile, operation.target) ?: return
        val canonical = acknowledgement.optJSONObject("state")
        val later =
            dao.targetOperations(operation.profile, operation.target).filter {
                it.sequence > operation.sequence
            }
        val field = playlistField(operation)
        val metadata = JSONObject(row.metadata)
        if (canonical != null) {
            val summaryFields =
                setOf("name", "description", "isPrivate", "shareToken", "createdAt", "updatedAt")
            summaryFields.forEach { key ->
                val fieldForKey = if (key == "shareToken") "sharing" else key
                if (canonical.has(key) && later.none { playlistField(it) == fieldForKey })
                    metadata.put(key, canonical.get(key))
            }
            val membershipPending = later.any { playlistField(it).startsWith("member:") }
            if (canonical.has("itemCount") && !membershipPending)
                metadata.put("itemCount", canonical.getInt("itemCount"))
            val stateRevisions = metadata.optJSONObject("stateRevisions") ?: JSONObject()
            acknowledgement.optJSONObject("revision")?.let { stateRevisions.put(field, it) }
            metadata.put("stateRevisions", stateRevisions)

            if (operation.kind == "playlist_add" && !canonical.isNull("entryId")) {
                val track = JSONObject(operation.payload).optString("trackId")
                if (track.isNotBlank()) {
                    dao.playlistEntry(operation.profile, operation.target, track)?.let { entry ->
                        dao.putPlaylistEntries(
                            listOf(entry.copy(entryId = canonical.getString("entryId")))
                        )
                    }
                }
            }
        }

        val invalidatedMembership =
            outcome in setOf("superseded", "rejected") &&
                operation.kind in setOf("playlist_add", "playlist_remove", "playlist_order")
        if (operation.kind == "playlist_delete" && outcome == "applied")
            metadata.put("itemCount", 0)
        metadata.put("lastSyncOutcome", outcome)
        dao.putPlaylist(
            row.copy(
                metadata = metadata.toString(),
                expectedCount = metadata.optInt("itemCount", row.expectedCount),
                complete = row.complete && !invalidatedMembership,
            )
        )
    }

    private fun stateField(kind: String): String =
        when (kind) {
            "favorite",
            "unfavorite" -> "favorite"
            "follow",
            "unfollow" -> "following"
            "music_play" -> "playback"
            else -> "playback"
        }

    private fun playlistField(operation: OfflineOperation): String =
        when (operation.kind) {
            "playlist_add",
            "playlist_remove" -> "member:" + JSONObject(operation.payload).optString("trackId")
            "playlist_create" -> "create"
            "playlist_delete" -> "deleted"
            else -> operation.kind.removePrefix("playlist_")
        }

    private fun serverMillis(json: JSONObject): Long =
        runCatching { Instant.parse(json.getString("serverTime")).toEpochMilli() }
            .recoverCatching {
                OffsetDateTime.parse(json.getString("serverTime")).toInstant().toEpochMilli()
            }
            .getOrElse {
                throw IllegalStateException("Server returned an invalid clock anchor.", it)
            }

    private fun parseActivityMs(value: String): Long =
        runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

    private fun bootCount(): Long =
        runCatching {
                Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
            }
            .getOrDefault(-1)
            .toLong()

    private fun normalizeServer(value: String): String = value.trim().trimEnd('/')

    private fun Double.finiteNonNegative(): Double = takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

    companion object {
        const val BATCH_SIZE = 100
        private const val MAX_BATCHES_PER_PASS = 5
        private val TERMINAL_OUTCOMES =
            setOf("applied", "superseded", "duplicate", "privacy_blocked", "rejected")
        private val PLAYBACK_KINDS = setOf("checkpoint", "complete", "watched", "unwatched")
        private val PLAYBACK_OVERLAY_KEYS =
            setOf(
                "positionSeconds",
                "durationSeconds",
                "played",
                "playedPercentage",
                "lastPlayedAt",
            )
    }
}

data class OfflineSyncPass(val acknowledgedCount: Int, val outcomes: Map<String, String>)

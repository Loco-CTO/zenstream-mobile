package com.zenstream.zenstreammobile.offline

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.room.withTransaction
import com.zenstream.zenstreammobile.data.catalogMediaItem
import com.zenstream.zenstreammobile.model.MediaItem
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import org.json.JSONObject

internal fun offlinePlaybackCheckpointProfileId(
    isLocalPlayback: Boolean,
    localSourceProfileId: String?,
    activeOfflineProfileId: String?,
): String? = if (isLocalPlayback) localSourceProfileId else activeOfflineProfileId

/** Applies local playback state and its future server operation in one Room transaction. */
class OfflinePlaybackStateRepository(
    private val context: Context,
    private val database: OfflineDatabase,
) {
    private val dao = database.offline()

    suspend fun checkpoint(
        profileId: String,
        target: String,
        positionSeconds: Double,
        durationSeconds: Double,
        historyEnabled: Boolean,
    ) {
        val position = positionSeconds.finiteNonNegative()
        val duration = durationSeconds.finiteNonNegative()
        database.withTransaction {
            val profile = requireProfile(profileId).copy(historyEnabled = historyEnabled)
            dao.putProfile(profile)
            val activity = activity(profile)
            val current = dao.playbackState(profileId, target)
            val played = current?.played ?: effectiveState(profileId, target).optBoolean("played")
            val next =
                OfflinePlaybackState(
                    profileId,
                    target,
                    if (duration > 0) position.coerceAtMost(duration) else position,
                    duration,
                    played,
                    activity.first,
                    current?.previousEpisodeId,
                    current?.nextEpisodeId,
                )
            dao.putPlaybackState(next)
            writeUserState(profileId, next, activity.first, historyEnabled)
            if (historyEnabled) {
                appendOperation(
                    profile,
                    target,
                    "checkpoint",
                    activity.first,
                    activity.second,
                    JSONObject()
                        .put("positionSeconds", next.positionSeconds)
                        .put("durationSeconds", next.durationSeconds),
                )
            }
        }
    }

    suspend fun complete(
        profileId: String,
        target: String,
        durationSeconds: Double,
        historyEnabled: Boolean,
    ) {
        database.withTransaction {
            val profile = requireProfile(profileId).copy(historyEnabled = historyEnabled)
            dao.putProfile(profile)
            val activity = activity(profile)
            val current = dao.playbackState(profileId, target)
            val played = historyEnabled || current?.played == true
            val next =
                OfflinePlaybackState(
                    profileId,
                    target,
                    0.0,
                    durationSeconds.finiteNonNegative(),
                    played,
                    activity.first,
                    current?.previousEpisodeId,
                    current?.nextEpisodeId,
                )
            dao.putPlaybackState(next)
            writeUserState(profileId, next, activity.first, historyEnabled)
            if (historyEnabled) {
                appendOperation(
                    profile,
                    target,
                    "complete",
                    activity.first,
                    activity.second,
                    JSONObject(),
                )
            }
        }
    }

    suspend fun setPlayed(profileId: String, target: String, played: Boolean): Boolean =
        database.withTransaction {
            val profile = requireProfile(profileId)
            val current = dao.playbackState(profileId, target)
            val effective = effectiveState(profileId, target)
            if (current?.played == played && current.positionSeconds == 0.0)
                return@withTransaction false
            val activity = activity(profile)
            val next =
                OfflinePlaybackState(
                    profileId,
                    target,
                    0.0,
                    current?.durationSeconds ?: effective.optDouble("durationSeconds", 0.0),
                    played,
                    activity.first,
                    current?.previousEpisodeId,
                    current?.nextEpisodeId,
                )
            dao.putPlaybackState(next)
            writeUserState(profileId, next, activity.first, historyEnabled = true)
            appendOperation(
                profile,
                target,
                if (played) "watched" else "unwatched",
                activity.first,
                activity.second,
                JSONObject(),
            )
            true
        }

    suspend fun markDownloadedEpisodes(profileId: String, rootId: String, played: Boolean): Int {
        requireProfile(profileId)
        var offset = 0
        var changed = 0
        while (true) {
            val ids = dao.downloadedEpisodeIds(profileId, rootId, 100, offset)
            if (ids.isEmpty()) return changed
            database.withTransaction {
                for (id in ids) {
                    val profile = requireProfile(profileId)
                    val current = dao.playbackState(profileId, id)
                    if (current?.played == played && current.positionSeconds == 0.0) continue
                    val activity = activity(profile)
                    val next =
                        OfflinePlaybackState(
                            profileId,
                            id,
                            0.0,
                            current?.durationSeconds ?: 0.0,
                            played,
                            activity.first,
                            current?.previousEpisodeId,
                            current?.nextEpisodeId,
                        )
                    dao.putPlaybackState(next)
                    writeUserState(profileId, next, activity.first, historyEnabled = true)
                    appendOperation(
                        profile,
                        id,
                        if (played) "watched" else "unwatched",
                        activity.first,
                        activity.second,
                        JSONObject(),
                    )
                    changed++
                }
            }
            offset += ids.size
        }
    }

    suspend fun downloadedEpisodeWatchCounts(profileId: String, rootId: String) =
        dao.downloadedEpisodeWatchCounts(profileId, rootId)

    suspend fun saveEpisodeNeighbors(
        profileId: String,
        target: String,
        previousId: String?,
        nextId: String?,
    ) {
        database.withTransaction {
            requireProfile(profileId)
            val current =
                dao.playbackState(profileId, target) ?: OfflinePlaybackState(profileId, target)
            dao.putPlaybackState(
                current.copy(previousEpisodeId = previousId, nextEpisodeId = nextId)
            )
        }
    }

    suspend fun continueWatching(profileId: String, limit: Int = 20): List<MediaItem> {
        requireProfile(profileId)
        return dao.continueWatching(profileId, limit.coerceIn(1, 100)).mapNotNull { state ->
            catalogItem(profileId, state.target)
        }
    }

    suspend fun nextUp(profileId: String, limit: Int = 20): List<MediaItem> {
        requireProfile(profileId)
        val completions = dao.completedDownloadedEpisodes(profileId, 500)
        val seenSeries = HashSet<String>()
        val result = ArrayList<MediaItem>()
        for (completion in completions) {
            if (result.size >= limit.coerceIn(1, 100)) break
            val series = dao.episodeSeriesId(profileId, completion.target) ?: continue
            if (!seenSeries.add(series)) continue
            val nextId = completion.nextEpisodeId ?: continue
            if (dao.playable(profileId, nextId).isEmpty()) continue
            catalogItem(profileId, nextId)?.let(result::add)
        }
        return result
    }

    suspend fun musicPlay(
        profileId: String,
        target: String,
        playbackInstanceId: String,
        historyEnabled: Boolean,
    ): Boolean = database.withTransaction {
        val profile = requireProfile(profileId).copy(historyEnabled = historyEnabled)
        dao.putProfile(profile)
        if (!historyEnabled || dao.operation(profileId, playbackInstanceId) != null)
            return@withTransaction false
        require(UUID.fromString(playbackInstanceId).toString() == playbackInstanceId)
        val activity = activity(profile)
        val state = dao.state(profileId, target) ?: OfflineUserState(profileId, target)
        val canonical = JSONObject(state.canonical)
        val overlay = JSONObject(state.overlay)
        val playCount = overlay.optInt("playCount", canonical.optInt("playCount", 0)) + 1
        val at = Instant.ofEpochMilli(activity.first).toString()
        overlay.put("playCount", playCount).put("lastPlayedAt", at)
        dao.putState(state.copy(overlay = overlay.toString(), localOnlyResume = null))
        val current = dao.playbackState(profileId, target)
        dao.putPlaybackState(
            (current ?: OfflinePlaybackState(profileId, target)).copy(activityMs = activity.first)
        )
        appendOperation(
            profile,
            target,
            "music_play",
            activity.first,
            activity.second,
            JSONObject(),
            operationId = playbackInstanceId,
        )
        true
    }

    suspend fun setFavorite(profileId: String, target: String, favorite: Boolean): Boolean =
        setFlag(profileId, target, "favorite", favorite)

    suspend fun setFollowing(profileId: String, target: String, following: Boolean): Boolean =
        setFlag(profileId, target, "following", following)

    /** Queue an online flag mutation even when the item itself has no downloaded catalog row. */
    internal suspend fun setAccountFlag(
        profileId: String,
        target: String,
        field: String,
        value: Boolean,
    ): Boolean = database.withTransaction {
        require(field == "favorite" || field == "following")
        val profile = requireProfile(profileId)
        val current = effectiveState(profileId, target)
        if (current.has(field) && current.optBoolean(field) == value) return@withTransaction false
        val state = dao.state(profileId, target) ?: OfflineUserState(profileId, target)
        dao.putState(state.copy(overlay = JSONObject(state.overlay).put(field, value).toString()))
        val activity = activity(profile)
        appendOperation(
            profile,
            target,
            when (field) {
                "favorite" -> if (value) "favorite" else "unfavorite"
                else -> if (value) "follow" else "unfollow"
            },
            activity.first,
            activity.second,
            JSONObject(),
            field = field,
        )
        true
    }

    suspend fun hasPendingFlags(profileId: String, target: String): Boolean {
        requireProfile(profileId)
        if (dao.hasPendingFlagOperations(profileId, target)) return true
        val item = dao.item(profileId, target)
        val seriesId = if (item?.type == "episode") dao.episodeSeriesId(profileId, target) else null
        return seriesId?.let { dao.hasPendingFlagOperations(profileId, it) } ?: false
    }

    private suspend fun setFlag(
        profileId: String,
        target: String,
        field: String,
        value: Boolean,
    ): Boolean = database.withTransaction {
        val profile = requireProfile(profileId)
        val sourceItem =
            requireNotNull(dao.item(profileId, target)) { "Downloaded item is missing." }
        val operationTarget =
            if (field == "following" && sourceItem.type == "episode")
                requireNotNull(dao.episodeSeriesId(profileId, target)) {
                    "The episode's series is unavailable."
                }
            else target
        val item =
            requireNotNull(dao.item(profileId, operationTarget)) { "Downloaded item is missing." }
        if (field == "following")
            require(item.type in setOf("movie", "series", "artist")) {
                "Follow is available for movies, series, and artists."
            }
        val current = effectiveState(profileId, operationTarget)
        if (current.has(field) && current.optBoolean(field) == value) return@withTransaction false
        val userState =
            dao.state(profileId, operationTarget) ?: OfflineUserState(profileId, operationTarget)
        val overlay = JSONObject(userState.overlay).put(field, value)
        dao.putState(userState.copy(overlay = overlay.toString()))
        val activity = activity(profile)
        appendOperation(
            profile,
            operationTarget,
            when (field) {
                "favorite" -> if (value) "favorite" else "unfavorite"
                "following" -> if (value) "follow" else "unfollow"
                else -> error("Unsupported offline account field.")
            },
            activity.first,
            activity.second,
            JSONObject(),
            field = field,
        )
        true
    }

    private suspend fun appendOperation(
        profile: OfflineProfile,
        target: String,
        kind: String,
        activityMs: Long,
        trusted: Boolean,
        payload: JSONObject,
        operationId: String = UUID.randomUUID().toString(),
        field: String = operationField(kind),
    ) {
        if (kind == "checkpoint") {
            val latest = dao.targetOperations(profile.id, target).lastOrNull()
            if (latest?.kind == "checkpoint" && !latest.inFlight) {
                dao.putOperation(
                    latest.copy(
                        activityMs = activityMs,
                        clockTrusted = trusted,
                        baseRevision = fieldRevision(profile.id, target, field),
                        historyEpoch = profile.historyEpoch,
                        privacyEpoch = profile.privacyEpoch,
                        payload = payload.toString(),
                    )
                )
                return
            }
        }
        val sequence = profile.nextSequence
        dao.putProfile(profile.copy(nextSequence = sequence + 1))
        dao.insertOperation(
            OfflineOperation(
                profile.id,
                sequence,
                operationId,
                target,
                kind,
                activityMs,
                trusted,
                fieldRevision(profile.id, target, field),
                profile.historyEpoch,
                profile.privacyEpoch,
                payload.toString(),
            )
        )
    }

    /** Enqueues a non-playback account operation inside the caller's Room transaction. */
    internal suspend fun appendAccountOperation(
        profileId: String,
        target: String,
        kind: String,
        baseRevision: Long,
        payload: JSONObject,
        operationId: String = UUID.randomUUID().toString(),
    ): String {
        val profile = requireProfile(profileId)
        val activity = activity(profile)
        val sequence = profile.nextSequence
        dao.putProfile(profile.copy(nextSequence = sequence + 1))
        dao.insertOperation(
            OfflineOperation(
                profile = profile.id,
                sequence = sequence,
                id = operationId,
                target = target,
                kind = kind,
                activityMs = activity.first,
                clockTrusted = activity.second,
                baseRevision = baseRevision,
                historyEpoch = profile.historyEpoch,
                privacyEpoch = profile.privacyEpoch,
                payload = payload.toString(),
            )
        )
        return operationId
    }

    private suspend fun fieldRevision(profileId: String, target: String, field: String): Long {
        val revision = JSONObject(dao.state(profileId, target)?.revisions ?: "{}").opt(field)
        val objectRevision = (revision as? JSONObject)?.optLong("revision")
        return objectRevision ?: (revision as? Number)?.toLong() ?: 0L
    }

    private fun operationField(kind: String): String =
        when (kind) {
            "favorite",
            "unfavorite" -> "favorite"
            "follow",
            "unfollow" -> "following"
            else -> "playback"
        }

    private suspend fun writeUserState(
        profileId: String,
        value: OfflinePlaybackState,
        activityMs: Long,
        historyEnabled: Boolean,
    ) {
        val current =
            dao.state(profileId, value.target) ?: OfflineUserState(profileId, value.target)
        val playback = playbackFields(value, activityMs)
        if (historyEnabled) {
            val overlay = JSONObject(current.overlay)
            playback.keys().forEach { key -> overlay.put(key, playback.get(key)) }
            dao.putState(current.copy(overlay = overlay.toString(), localOnlyResume = null))
        } else {
            dao.putState(current.copy(localOnlyResume = playback.toString()))
        }
    }

    private fun playbackFields(value: OfflinePlaybackState, activityMs: Long): JSONObject {
        val percent =
            if (value.durationSeconds > 0.0) value.positionSeconds / value.durationSeconds * 100.0
            else 0.0
        return JSONObject()
            .put("positionSeconds", value.positionSeconds)
            .put("durationSeconds", value.durationSeconds)
            .put("played", value.played)
            .put("playedPercentage", percent.coerceIn(0.0, 100.0))
            .put("lastPlayedAt", Instant.ofEpochMilli(activityMs).toString())
    }

    private suspend fun effectiveState(profileId: String, target: String): JSONObject {
        val state = dao.state(profileId, target)
        val result = JSONObject(state?.canonical ?: "{}")
        JSONObject(state?.overlay ?: "{}").keys().forEach { key ->
            result.put(key, JSONObject(state?.overlay ?: "{}").get(key))
        }
        state?.localOnlyResume?.let { local ->
            val value = JSONObject(local)
            value.keys().forEach { key -> result.put(key, value.get(key)) }
        }
        return result
    }

    private suspend fun catalogItem(profileId: String, target: String): MediaItem? {
        val row = dao.item(profileId, target) ?: return null
        val state = dao.state(profileId, target)
        val merged = JSONObject(state?.canonical ?: "{}")
        listOf(state?.overlay, state?.localOnlyResume).filterNotNull().forEach { json ->
            val values = JSONObject(json)
            values.keys().forEach { key -> merged.put(key, values.get(key)) }
        }
        return catalogMediaItem(JSONObject(row.payload).put("userState", merged))
    }

    private suspend fun requireProfile(id: String): OfflineProfile =
        requireNotNull(dao.profile(id)) { "Offline profile is unavailable." }
            .also { check(!it.locked) { "Sign in to unlock these downloads." } }

    /** Use a server-time anchor only while its monotonic clock and wall clock remain plausible. */
    private fun activity(profile: OfflineProfile): Pair<Long, Boolean> {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val boot =
            runCatching {
                    Settings.Global.getInt(
                        context.contentResolver,
                        Settings.Global.BOOT_COUNT,
                        -1,
                    )
                }
                .getOrDefault(-1)
                .toLong()
        val anchored =
            profile.anchorServerMs > 0 &&
                profile.anchorElapsedMs >= 0 &&
                profile.anchorBoot >= 0 &&
                boot >= 0 &&
                profile.anchorBoot == boot &&
                elapsed >= profile.anchorElapsedMs
        if (!anchored) return wall to false
        val estimatedServer = profile.anchorServerMs + (elapsed - profile.anchorElapsedMs)
        val trusted = abs(wall - estimatedServer) <= MAX_CLOCK_DRIFT_MS
        return (if (trusted) estimatedServer else wall) to trusted
    }

    private fun Double.finiteNonNegative(): Double = takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

    private companion object {
        const val MAX_CLOCK_DRIFT_MS = 5 * 60 * 1000L
    }
}

package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.model.SyncplayGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max

internal data class SyncplayTimelineTarget(
    val positionSeconds: Double,
    val shouldPlay: Boolean,
    val startDelayMillis: Long? = null,
)

internal fun syncplayTimelineTarget(
    room: SyncplayGroup,
    serverNow: Double,
): SyncplayTimelineTarget {
    val waitingForStart = room.playbackState == "playing" && serverNow < room.effectiveAt
    val shouldPlay = room.playbackState == "playing" && !waitingForStart
    val position = if (shouldPlay) {
        room.anchorPosition + max(0.0, serverNow - room.anchorServerTime)
    } else {
        room.anchorPosition
    }
    val startDelayMillis = if (waitingForStart) {
        ceil((room.effectiveAt - serverNow) * 1_000.0).toLong() + START_GRACE_MILLIS
    } else {
        null
    }
    return SyncplayTimelineTarget(position, shouldPlay, startDelayMillis)
}

internal fun sameSyncplayTimeline(left: SyncplayGroup, right: SyncplayGroup): Boolean =
    left.id == right.id &&
        left.itemId == right.itemId &&
        left.mediaGeneration == right.mediaGeneration &&
        left.timelineRevision == right.timelineRevision

internal class SyncplayTimelineScheduler(
    private val scope: CoroutineScope,
    private val serverNow: () -> Double,
    private val currentRoom: () -> SyncplayGroup?,
    private val apply: (SyncplayGroup, Double) -> Unit,
) {
    private var pendingStart: Job? = null

    fun apply(room: SyncplayGroup) {
        pendingStart?.cancel()
        val now = serverNow()
        val target = syncplayTimelineTarget(room, now)
        apply(room, now)
        val delayMillis = target.startDelayMillis ?: return
        pendingStart = scope.launch {
            delay(delayMillis)
            currentRoom()
                ?.takeIf { sameSyncplayTimeline(it, room) }
                ?.let { current -> apply(current, serverNow()) }
        }
    }

    fun cancel() {
        pendingStart?.cancel()
        pendingStart = null
    }
}

private const val START_GRACE_MILLIS = 20L

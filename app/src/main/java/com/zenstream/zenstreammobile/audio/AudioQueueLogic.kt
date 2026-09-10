package com.zenstream.zenstreammobile.audio

import com.zenstream.zenstreammobile.model.AudioPlayerState
import com.zenstream.zenstreammobile.model.AudioQueueEntry
import com.zenstream.zenstreammobile.model.AudioRepeatMode
import kotlin.random.Random

internal data class QueueRemoval(
    val entries: List<AudioQueueEntry>,
    val currentIndex: Int,
    val removedCurrent: Boolean,
)

internal fun nextQueueIndex(
    queueSize: Int,
    currentIndex: Int,
    repeatMode: AudioRepeatMode,
    force: Boolean,
): Int? {
    if (queueSize <= 0) return null
    val current = currentIndex.coerceIn(0, queueSize - 1)
    if (repeatMode == AudioRepeatMode.Track && force) return current
    if (current + 1 < queueSize) return current + 1
    return if (repeatMode == AudioRepeatMode.Queue) 0 else null
}

internal fun previousQueueIndex(
    queueSize: Int,
    currentIndex: Int,
): Int? {
    if (queueSize <= 0) return null
    val current = currentIndex.coerceIn(0, queueSize - 1)
    return (current - 1).takeIf { it >= 0 }
}

internal fun removeQueueEntry(
    state: AudioPlayerState,
    entryId: String,
): QueueRemoval? {
    val removedIndex = state.queue.indexOfFirst { it.entryId == entryId }
    if (removedIndex < 0) return null
    val remaining = state.queue.filterNot { it.entryId == entryId }
    if (remaining.isEmpty()) return QueueRemoval(emptyList(), -1, removedCurrent = true)
    val currentIndex =
        when {
            removedIndex < state.currentIndex -> state.currentIndex - 1
            state.currentIndex >= remaining.size -> remaining.lastIndex
            else -> state.currentIndex
        }
    return QueueRemoval(
        entries = remaining,
        currentIndex = currentIndex.coerceIn(0, remaining.lastIndex),
        removedCurrent = removedIndex == state.currentIndex,
    )
}

internal fun reorderQueue(
    state: AudioPlayerState,
    from: Int,
    to: Int,
): AudioPlayerState? {
    if (from !in state.queue.indices || to !in state.queue.indices || from == to) return null
    val values = state.queue.toMutableList()
    val moved = values.removeAt(from)
    values.add(to, moved)
    val currentId = state.currentEntry?.entryId
    return state.copy(
        queue = values,
        currentIndex = values.indexOfFirst { it.entryId == currentId }.coerceAtLeast(0),
    )
}

internal fun shuffleQueueKeepingCurrent(
    state: AudioPlayerState,
    random: Random = Random.Default,
): AudioPlayerState {
    val current = state.currentEntry ?: return state.copy(shuffle = true)
    val remaining = state.queue.filterNot { it.entryId == current.entryId }.shuffled(random)
    return state.copy(
        queue = listOf(current) + remaining,
        currentIndex = 0,
        shuffle = true,
    )
}

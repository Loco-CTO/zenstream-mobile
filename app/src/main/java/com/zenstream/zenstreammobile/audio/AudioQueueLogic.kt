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

internal data class QueueAdvanceSelection(
    val index: Int,
    val playedEntryIds: Set<String>,
    val resetPlayed: Boolean = false,
)

internal data class QueuePlaybackOrder(
    val entries: List<AudioQueueEntry>,
    val currentIndex: Int,
)

internal fun queuePlaybackOrder(
    entries: List<AudioQueueEntry>,
    selectedIndex: Int,
    shuffle: Boolean,
    random: Random = Random.Default,
): QueuePlaybackOrder {
    if (entries.isEmpty()) return QueuePlaybackOrder(emptyList(), -1)
    if (shuffle) return QueuePlaybackOrder(entries.shuffled(random), currentIndex = 0)

    val selectedEntryId = entries[selectedIndex.coerceIn(0, entries.lastIndex)].entryId
    return QueuePlaybackOrder(
        entries = entries,
        currentIndex = entries.indexOfFirst { it.entryId == selectedEntryId }.coerceAtLeast(0),
    )
}

internal fun queueIndexForEntry(
    queue: List<AudioQueueEntry>,
    entryId: String,
): Int? = queue.indexOfFirst { it.entryId == entryId }.takeIf { it >= 0 }

internal fun selectQueueEntryForPlayback(
    state: AudioPlayerState,
    entryId: String,
): AudioPlayerState? {
    val targetIndex = queueIndexForEntry(state.queue, entryId) ?: return null
    return state.copy(
        currentIndex = targetIndex,
        positionSeconds = 0L,
        durationSeconds = 0L,
        isPlaying = false,
        isLoading = true,
        error = null,
        sourceFormat = null,
        sourceBitrate = null,
        sourceSampleRate = null,
        playbackMode = null,
    )
}

internal fun markQueueEntryPlayed(
    state: AudioPlayerState,
    entryId: String,
): AudioPlayerState? {
    if (queueIndexForEntry(state.queue, entryId) == null) return null
    val queueEntryIds = state.queue.mapTo(mutableSetOf()) { it.entryId }
    return state.copy(
        playedEntryIds = state.playedEntryIds.intersect(queueEntryIds) + entryId,
    )
}

internal fun nextQueueSelection(
    state: AudioPlayerState,
    force: Boolean,
    random: Random = Random.Default,
): QueueAdvanceSelection? {
    if (state.queue.isEmpty()) return null
    val currentIndex = state.currentIndex.coerceIn(0, state.queue.lastIndex)
    val queueEntryIds = state.queue.mapTo(mutableSetOf()) { it.entryId }
    val playedEntryIds = state.playedEntryIds.intersect(queueEntryIds)
    if (state.repeatMode == AudioRepeatMode.Track && force) {
        return QueueAdvanceSelection(currentIndex, playedEntryIds)
    }

    val unplayed = state.queue.indices.filter { state.queue[it].entryId !in playedEntryIds }
    if (state.shuffle) {
        val candidates = unplayed.filterNot { it == currentIndex }.ifEmpty { unplayed }
        if (candidates.isNotEmpty()) {
            return QueueAdvanceSelection(candidates[random.nextInt(candidates.size)], playedEntryIds)
        }
        if (state.queue[currentIndex].entryId !in playedEntryIds) {
            return QueueAdvanceSelection(currentIndex, playedEntryIds)
        }
        if (state.repeatMode == AudioRepeatMode.Queue) {
            val resetCandidates = state.queue.indices.filterNot { it == currentIndex }.ifEmpty {
                state.queue.indices.toList()
            }
            return QueueAdvanceSelection(
                index = resetCandidates[random.nextInt(resetCandidates.size)],
                playedEntryIds = emptySet(),
                resetPlayed = true,
            )
        }
        return null
    }

    val orderedAfterCurrent =
        ((currentIndex + 1)..state.queue.lastIndex).toList() + (0 until currentIndex).toList()
    val nextUnplayed = orderedAfterCurrent.firstOrNull { it in unplayed }
    if (nextUnplayed != null) return QueueAdvanceSelection(nextUnplayed, playedEntryIds)
    if (state.queue[currentIndex].entryId !in playedEntryIds) {
        return QueueAdvanceSelection(currentIndex, playedEntryIds)
    }
    if (state.repeatMode == AudioRepeatMode.Queue) {
        return QueueAdvanceSelection(
            index = orderedAfterCurrent.firstOrNull() ?: currentIndex,
            playedEntryIds = emptySet(),
            resetPlayed = true,
        )
    }
    return null
}

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

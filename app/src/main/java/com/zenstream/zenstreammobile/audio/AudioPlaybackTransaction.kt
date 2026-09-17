package com.zenstream.zenstreammobile.audio

import kotlinx.coroutines.Job

/** Immutable identity for one cancellable audio source negotiation. */
internal data class PlaybackRequest(
    val generation: Long,
    val entryId: String,
    val autoPlay: Boolean,
    val positionMs: Long,
)

/**
 * Owns selection identity and cancellation, without owning the Media3 player or queue state.
 *
 * The service uses this from its short state-mutation sections. Network and Media3 preparation jobs
 * use [isCurrent] after every suspension before they publish or mutate the player.
 */
internal class AudioPlaybackTransactionController {
    private var nextGeneration = 0L
    private var latestSelectionSequence = 0L

    var activeLoadJob: Job? = null
        private set

    var activeRecoveryJob: Job? = null
        private set

    @Synchronized fun currentGeneration(): Long = nextGeneration

    @Synchronized
    fun acceptsCommand(commandSequence: Long): Boolean =
        commandSequence <= 0L || commandSequence >= latestSelectionSequence

    @Synchronized
    fun acceptSelection(
        commandSequence: Long,
        entryId: String,
        autoPlay: Boolean,
        positionMs: Long,
    ): PlaybackRequest? {
        if (commandSequence > 0L && commandSequence <= latestSelectionSequence) return null
        if (commandSequence > 0L) latestSelectionSequence = commandSequence
        cancelJobsLocked()
        val request =
            PlaybackRequest(
                generation = ++nextGeneration,
                entryId = entryId,
                autoPlay = autoPlay,
                positionMs = positionMs.coerceAtLeast(0L),
            )
        return request
    }

    @Synchronized
    fun invalidate(commandSequence: Long = 0L): Boolean {
        if (commandSequence > 0L && commandSequence < latestSelectionSequence) return false
        if (commandSequence > 0L) latestSelectionSequence = commandSequence
        cancelJobsLocked()
        nextGeneration += 1L
        return true
    }

    @Synchronized
    fun isCurrent(request: PlaybackRequest, currentEntryId: String?): Boolean =
        request.generation == nextGeneration && request.entryId == currentEntryId

    @Synchronized
    fun registerLoad(job: Job) {
        activeLoadJob = job
    }

    @Synchronized
    fun registerRecovery(job: Job) {
        activeRecoveryJob = job
    }

    @Synchronized
    fun clearLoad(job: Job) {
        if (activeLoadJob === job) activeLoadJob = null
    }

    @Synchronized
    fun clearRecovery(job: Job) {
        if (activeRecoveryJob === job) activeRecoveryJob = null
    }

    @Synchronized
    fun cancelRecovery() {
        activeRecoveryJob?.cancel()
        activeRecoveryJob = null
    }

    @Synchronized
    private fun cancelJobsLocked() {
        activeLoadJob?.cancel()
        activeLoadJob = null
        activeRecoveryJob?.cancel()
        activeRecoveryJob = null
    }
}

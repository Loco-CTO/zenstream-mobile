package com.zenstream.zenstreammobile.ui

import kotlinx.coroutines.delay

/** One access retry per failure sequence; working playback permits a later lease to expire. */
internal class PlaybackAccessRecovery {
    private var identity: String? = null
    private var attempted = false
    private var recoveryPosition = 0.0

    fun reset() {
        identity = null
        attempted = false
    }

    fun tryRecover(
        identity: String,
        httpStatus: Int?,
        local: Boolean,
        networkAllowed: Boolean,
        positionSeconds: Double,
    ): Boolean {
        if (local || !networkAllowed || httpStatus !in setOf(401, 403)) return false
        if (this.identity != identity) {
            this.identity = identity
            attempted = false
        }
        if (attempted) return false
        attempted = true
        recoveryPosition = positionSeconds
        return true
    }

    fun observeProgress(
        identity: String,
        positionSeconds: Double,
        playing: Boolean,
        pausedReady: Boolean = false,
    ) {
        if (this.identity != identity) reset()
        if (
            this.identity == identity &&
                (pausedReady || playing && positionSeconds >= recoveryPosition + 2.0)
        ) {
            attempted = false
        }
    }
}

/** Retry failures after 30 seconds, without also waiting the normal renewal interval. */
internal suspend fun refreshPlaybackAccessLoop(
    expiresInSeconds: Long,
    immediately: Boolean = false,
    refresh: suspend () -> Long?,
) {
    fun renewalDelay(seconds: Long): Long {
        val lifetime = seconds.coerceIn(1, Long.MAX_VALUE / 1_000) * 1_000
        return lifetime - minOf(60_000, lifetime / 2)
    }
    var nextDelay = if (immediately) 0L else renewalDelay(expiresInSeconds)
    while (true) {
        delay(nextDelay)
        nextDelay = refresh()?.let(::renewalDelay) ?: 30_000L
    }
}

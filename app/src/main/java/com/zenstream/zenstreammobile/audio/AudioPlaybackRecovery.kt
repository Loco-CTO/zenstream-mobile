package com.zenstream.zenstreammobile.audio

/**
 * Chooses the safest position to use when rebuilding a failed player. Media3 may report zero after
 * a source error has torn down its current period, so the service also carries the last observed
 * position and the position published to the UI.
 */
internal fun selectRecoveryPositionMs(
    playerPositionMs: Long,
    lastKnownPositionMs: Long,
    publishedPositionSeconds: Long,
): Long {
    val publishedPositionMs =
        publishedPositionSeconds
            .coerceAtLeast(0L)
            .coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
    return maxOf(
        playerPositionMs.coerceAtLeast(0L),
        lastKnownPositionMs.coerceAtLeast(0L),
        publishedPositionMs,
    )
}

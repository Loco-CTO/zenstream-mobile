package com.zenstream.zenstreammobile.offline

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Runs as a child of the transfer owner, never on the blocked queue-pass coroutine. */
internal suspend fun monitorDownloadNotifications(
    read: suspend (Long) -> Pair<DownloadNotificationQueue, List<DownloadNotificationItem>>?,
    enabled: () -> Boolean,
    elapsedMillis: () -> Long,
    wallMillis: () -> Long,
    publish: (DownloadNotificationProgress) -> Unit,
) {
    val estimator = DownloadNotificationEstimator()
    var previous: DownloadNotificationProgress? = null
    var previouslyEnabled = false
    while (currentCoroutineContext().isActive) {
        delay(1000)
        val now = wallMillis()
        val snapshot = read(now) ?: return
        val value = estimator.update(snapshot.first, snapshot.second, elapsedMillis(), now)
        val available = enabled()
        if (value != previous || available && !previouslyEnabled) {
            publish(value)
            previous = value
        }
        previouslyEnabled = available
    }
}

package com.zenstream.zenstreammobile.offline

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadNotificationMonitorTest {
    @Test
    fun aLongRunningQueuePassStillPublishesOncePerSecondAndStopsWithItsOwner() = runTest {
        val posted = mutableListOf<DownloadNotificationProgress>()
        var reads = 0
        val owner = launch {
            val monitor = launch {
                monitorDownloadNotifications(
                    read = {
                        reads++
                        DownloadNotificationQueue(1, 1) to
                            listOf(
                                DownloadNotificationItem(
                                    "file",
                                    "transferring",
                                    currentTime,
                                    100_000,
                                    0,
                                    "Film",
                                    "movie",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                )
                            )
                    },
                    enabled = { true },
                    elapsedMillis = { currentTime },
                    wallMillis = { currentTime },
                    publish = { posted += it },
                )
            }
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { monitor.cancelAndJoin() }
            }
        }
        advanceTimeBy(4500)
        assertEquals(4, reads)
        assertEquals(listOf(1000L, 2000L, 3000L, 4000L), posted.map { it.bytes })
        owner.cancelAndJoin()
        advanceTimeBy(5000)
        assertEquals(4, posted.size)
    }

    @Test
    fun permissionRecoveryRepostsUnchangedProgressAndOtherUnchangedTicksAreSuppressed() = runTest {
        var enabled = false
        var posts = 0
        val job = launch {
            monitorDownloadNotifications(
                read = { DownloadNotificationQueue(0, 0) to emptyList() },
                enabled = { enabled },
                elapsedMillis = { currentTime },
                wallMillis = { currentTime },
                publish = { posts++ },
            )
        }
        advanceTimeBy(3500)
        assertEquals(1, posts)
        enabled = true
        advanceTimeBy(1500)
        assertEquals(2, posts)
        job.cancelAndJoin()
    }
}

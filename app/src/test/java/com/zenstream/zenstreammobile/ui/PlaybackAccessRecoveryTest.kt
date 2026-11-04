package com.zenstream.zenstreammobile.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackAccessRecoveryTest {
    @Test
    fun aRecoveredPausedFramePermitsALaterLeaseExpiryWithoutPlaying() {
        val recovery = PlaybackAccessRecovery()
        assertTrue(recovery.tryRecover("s", 401, false, true, 8.0))
        recovery.observeProgress("s", 8.0, false)
        assertFalse(recovery.tryRecover("s", 401, false, true, 8.0))
        recovery.observeProgress("s", 8.0, false, pausedReady = true)
        assertTrue(recovery.tryRecover("s", 401, false, true, 8.0))
        assertFalse(recovery.tryRecover("s", 401, false, true, 8.0))
    }

    @Test
    fun rejectionIsRetriedOnceUntilPlaybackMakesProgress() {
        val recovery = PlaybackAccessRecovery()
        fun retry(position: Double = 100.0) =
            recovery.tryRecover("item:source", 401, false, true, position)
        assertTrue(retry())
        assertFalse(retry())
        recovery.observeProgress("item:source", 102.0, false)
        assertFalse(retry())
        recovery.observeProgress("item:source", 101.9, true)
        assertFalse(retry())
        recovery.observeProgress("item:source", 102.0, true)
        assertTrue(retry(102.0))
        assertFalse(retry(102.0))
    }

    @Test
    fun onlyRemoteAuthorizationFailuresRetryWithNetworkAllowed() {
        val recovery = PlaybackAccessRecovery()
        for (code in listOf(null, 404, 500, 503)) {
            assertFalse(recovery.tryRecover("s", code, false, true, 0.0))
        }
        assertFalse(recovery.tryRecover("s", 401, true, true, 0.0))
        assertFalse(recovery.tryRecover("s", 401, false, false, 0.0))
        assertTrue(recovery.tryRecover("s", 403, false, true, 0.0))
        assertFalse(recovery.tryRecover("s", 403, false, true, 0.0))
        assertTrue(recovery.tryRecover("another-source", 401, false, true, 0.0))
        recovery.reset()
        assertTrue(recovery.tryRecover("another-source", 401, false, true, 0.0))
    }

    @Test
    fun failedRenewalRetriesAfterThirtySecondsAndSuccessfulRenewalRestoresNormalInterval() =
        runTest {
            val attempts = mutableListOf<Long>()
            backgroundScope.launch {
                refreshPlaybackAccessLoop(900) {
                    attempts += testScheduler.currentTime
                    if (attempts.size == 1) null else 900
                }
            }
            advanceTimeBy(840_000)
            runCurrent()
            assertEquals(listOf(840_000L), attempts)
            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(1, attempts.size)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(840_000L, 870_000L), attempts)
            advanceTimeBy(840_000)
            runCurrent()
            assertEquals(listOf(840_000L, 870_000L, 1_710_000L), attempts)
        }

    @Test
    fun foregroundRenewalRunsImmediatelyAndCancellationStopsLaterRequests() = runTest {
        var attempts = 0
        val job = backgroundScope.launch {
            refreshPlaybackAccessLoop(900, immediately = true) {
                attempts++
                900
            }
        }
        runCurrent()
        assertEquals(1, attempts)
        job.cancel()
        advanceTimeBy(900_000)
        runCurrent()
        assertEquals(1, attempts)
    }

    @Test
    fun cancellationOfAnInFlightRenewalDoesNotScheduleRetries() = runTest {
        var attempts = 0
        val job = backgroundScope.launch {
            refreshPlaybackAccessLoop(900, immediately = true) {
                attempts++
                throw CancellationException("source replaced")
            }
        }
        runCurrent()
        assertTrue(job.isCancelled)
        advanceTimeBy(900_000)
        runCurrent()
        assertEquals(1, attempts)
    }
}

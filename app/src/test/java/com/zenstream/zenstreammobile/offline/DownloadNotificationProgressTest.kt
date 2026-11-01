package com.zenstream.zenstreammobile.offline

import org.junit.Assert.*
import org.junit.Test

class DownloadNotificationProgressTest {
    private fun item(
        id: String = "a",
        bytes: Long = 1000,
        total: Long = 10_000,
        state: String = "transferring",
        retryAt: Long = 0,
    ) =
        DownloadNotificationItem(
            id,
            state,
            bytes,
            total,
            retryAt,
            "Film",
            "movie",
            null,
            null,
            null,
            null,
            null,
            null,
        )

    private fun DownloadNotificationEstimator.tick(
        time: Long,
        vararg items: DownloadNotificationItem,
    ) = update(DownloadNotificationQueue(items.size + 4, items.size), items.toList(), time, time)

    @Test
    fun etaWarmsUpAndUsesResumedByteDeltas() {
        val estimator = DownloadNotificationEstimator()
        assertNull(estimator.tick(0, item(bytes = 5000)).remainingSeconds)
        val result = estimator.tick(3000, item(bytes = 8000))
        assertEquals(1000L, result.bytesPerSecond)
        assertEquals(2L, result.remainingSeconds)
        assertEquals(80, result.percent)
        assertEquals(5, result.pending)
    }

    @Test
    fun concurrentFilesAreByteWeightedAndAddingAFileDoesNotCountItsResumeOffsetAsSpeed() {
        val estimator = DownloadNotificationEstimator()
        estimator.tick(0, item())
        assertNull(
            estimator.tick(3000, item(bytes = 4000), item("b", 50_000, 100_000)).bytesPerSecond
        )
        val result = estimator.tick(6000, item(bytes = 7000), item("b", 56_000, 100_000))
        assertEquals(3000L, result.bytesPerSecond)
        assertEquals(63_000L, result.bytes)
        assertEquals(110_000L, result.total)
        assertEquals(57, result.percent)
        assertEquals(16L, result.remainingSeconds)
    }

    @Test
    fun stalledTransfersLoseStaleSpeedAndEta() {
        val estimator = DownloadNotificationEstimator()
        estimator.tick(0, item())
        assertNotNull(estimator.tick(3000, item(bytes = 4000)).remainingSeconds)
        val stalled = estimator.tick(8000, item(bytes = 4000))
        assertNull(stalled.remainingSeconds)
        assertNull(stalled.bytesPerSecond)
        assertEquals(40, stalled.percent)
    }

    @Test
    fun restartOrRepresentationChangeResetsTheEstimate() {
        val estimator = DownloadNotificationEstimator()
        estimator.tick(0, item())
        estimator.tick(3000, item(bytes = 4000))
        assertNull(estimator.tick(4000, item(bytes = 0)).remainingSeconds)
        assertNull(estimator.tick(8000, item(bytes = 5000, total = 20_000)).remainingSeconds)
    }

    @Test
    fun returningAfterALongSamplingGapRequiresAFreshEstimate() {
        val estimator = DownloadNotificationEstimator()
        estimator.tick(0, item())
        estimator.tick(3000, item(bytes = 4000))
        assertNull(estimator.tick(30_000, item(bytes = 7000)).remainingSeconds)
    }

    @Test
    fun unknownLengthsDoNotClaimAQueuePercentageOrEta() {
        val estimator = DownloadNotificationEstimator()
        estimator.tick(0, item(total = 0))
        val result = estimator.tick(3000, item(bytes = 4000, total = 0))
        assertEquals(1000L, result.bytesPerSecond)
        assertNull(result.total)
        assertNull(result.percent)
        assertNull(result.remainingSeconds)
    }

    @Test
    fun verificationAndPreparationAreIndeterminateAndDiscardOldSpeed() {
        val estimator = DownloadNotificationEstimator()
        estimator.tick(0, item())
        estimator.tick(3000, item(bytes = 4000))
        val verify = estimator.tick(4000, item(bytes = 10_000, state = "verifying"))
        assertEquals(DownloadNotificationPhase.VERIFYING, verify.phase)
        assertNull(verify.percent)
        assertNull(verify.remainingSeconds)
        assertEquals(
            DownloadNotificationPhase.PREPARING,
            estimator.tick(5000, item(state = "preparing")).phase,
        )
        assertNull(estimator.tick(6000, item(bytes = 5000)).bytesPerSecond)
    }

    @Test
    fun retryAndQueueWaitAreExplicit() {
        val estimator = DownloadNotificationEstimator()
        assertEquals(
            DownloadNotificationPhase.RETRYING,
            estimator.tick(1000, item(state = "queued", retryAt = 5000)).phase,
        )
        assertEquals(
            DownloadNotificationPhase.QUEUED,
            estimator.tick(6000, item(state = "queued", retryAt = 5000)).phase,
        )
    }

    @Test
    fun largeMediaCannotOverflowPercentageAndValuesAreClamped() {
        val estimator = DownloadNotificationEstimator()
        assertEquals(
            50,
            estimator.tick(0, item(bytes = Long.MAX_VALUE / 2, total = Long.MAX_VALUE)).percent,
        )
        assertEquals(100, estimator.tick(1000, item(bytes = 12_000)).percent)
    }
}

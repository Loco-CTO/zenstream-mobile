package com.zenstream.zenstreammobile.audio

import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlaybackTransactionTest {
    @Test
    fun newerSelectionCancelsTheOlderLoadAndBecomesCurrent() = runTest {
        val controller = AudioPlaybackTransactionController()
        val old = controller.acceptSelection(10L, "a", autoPlay = true, positionMs = 0L)
        assertNotNull(old)
        val oldLoad = launch { awaitCancellation() }
        controller.registerLoad(oldLoad)

        val latest = controller.acceptSelection(11L, "b", autoPlay = true, positionMs = 0L)

        assertTrue(oldLoad.isCancelled)
        assertNotNull(latest)
        assertFalse(controller.isCurrent(old!!, "a"))
        assertTrue(controller.isCurrent(latest!!, "b"))
    }

    @Test
    fun staleSelectionSequenceIsIgnored() {
        val controller = AudioPlaybackTransactionController()

        val latest = controller.acceptSelection(20L, "latest", autoPlay = true, positionMs = 0L)
        val stale = controller.acceptSelection(19L, "stale", autoPlay = true, positionMs = 0L)

        assertNotNull(latest)
        assertNull(stale)
        assertTrue(controller.isCurrent(latest!!, "latest"))
    }

    @Test
    fun invalidationMakesACompletedNetworkResultStale() {
        val controller = AudioPlaybackTransactionController()
        val request = controller.acceptSelection(1L, "a", autoPlay = true, positionMs = 0L)!!

        controller.invalidate(2L)

        assertFalse(controller.isCurrent(request, "a"))
    }

    @Test
    fun newerSelectionPreventsAnOlderTeardownFromFinalizing() {
        val controller = AudioPlaybackTransactionController()
        controller.acceptSelection(10L, "old", autoPlay = true, positionMs = 0L)
        controller.invalidate(11L)
        val teardownGeneration = controller.currentGeneration()

        assertTrue(controller.canFinalizeTeardown(11L, teardownGeneration))

        controller.acceptSelection(0L, "new", autoPlay = true, positionMs = 0L)

        assertFalse(controller.canFinalizeTeardown(11L, teardownGeneration))
    }

    @Test
    fun cancellationAlsoStopsAnActiveRecoveryJob() = runTest {
        val controller = AudioPlaybackTransactionController()
        val recovery: Job = launch { awaitCancellation() }
        controller.registerRecovery(recovery)

        controller.invalidate(3L)

        assertTrue(recovery.isCancelled)
    }
}

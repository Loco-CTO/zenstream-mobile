package com.zenstream.zenstreammobile.offline

import org.junit.Assert.*
import org.junit.Test

class DownloadExecutionPolicyTest {
    @Test
    fun retryDelayIsBoundedEvenForLongQueues() {
        assertEquals(1000L, transferRetryDelay(0))
        assertEquals(2000L, transferRetryDelay(1))
        assertEquals(60000L, transferRetryDelay(100))
    }

    @Test
    fun qualifyingTransfersUseUidtFromApi34AndLegacyServiceBelowIt() {
        for (api in listOf(24, 26, 30, 33)) assertEquals(
            TransferExecution.FOREGROUND_SERVICE,
            executionForApi(api),
        )
        for (api in listOf(34, 35, 36, 37)) assertEquals(
            TransferExecution.UIDT,
            executionForApi(api),
        )
    }
}

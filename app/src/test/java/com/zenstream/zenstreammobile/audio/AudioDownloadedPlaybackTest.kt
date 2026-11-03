package com.zenstream.zenstreammobile.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDownloadedPlaybackTest {
    @Test
    fun downloadedRetryClearsTheCurrentStreamOverride() {
        assertEquals(
            null,
            streamEntryIdAfterRetry(
                currentStreamEntryId = "entry-1",
                currentEntryId = "entry-1",
                stream = false,
                playDownloaded = true,
            ),
        )
    }

    @Test
    fun ordinaryRetryPreservesTheStreamOverrideAndStreamActionSetsIt() {
        assertEquals(
            "entry-1",
            streamEntryIdAfterRetry(
                currentStreamEntryId = "entry-1",
                currentEntryId = "entry-1",
                stream = false,
                playDownloaded = false,
            ),
        )
        assertEquals(
            "entry-2",
            streamEntryIdAfterRetry(
                currentStreamEntryId = "entry-1",
                currentEntryId = "entry-2",
                stream = true,
                playDownloaded = false,
            ),
        )
    }
}

package com.zenstream.zenstreammobile.offline

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DownloadRangeTest {
    private val digest = "a".repeat(64)

    @Test
    fun ignoredRangeRestartsInsteadOfAppending() {
        val result = validateTransferRange(50, 100, digest, 200, "\"$digest\"", null)
        assertFalse(result.append)
        assertFalse(result.verifyOnly)
    }

    @Test
    fun resumesOnlyAnIdenticalStrongRepresentation() {
        assertTrue(
            validateTransferRange(50, 100, digest, 206, "\"$digest\"", "bytes 50-99/100").append
        )
        for ((etag, range) in
            listOf(
                "W/\"$digest\"" to "bytes 50-99/100",
                "\"changed\"" to "bytes 50-99/100",
                "\"$digest\"" to "bytes 49-99/100",
                "\"$digest\"" to "bytes 50-99/101",
                null to "bytes 50-99/100",
            )) {
            assertThrows(RepresentationChanged::class.java) {
                validateTransferRange(50, 100, digest, 206, etag, range)
            }
        }
    }

    @Test
    fun range416CanOnlyVerifyAnAlreadyCompleteFile() {
        assertTrue(validateTransferRange(100, 100, digest, 416, null, null).verifyOnly)
        assertThrows(IOException::class.java) {
            validateTransferRange(50, 100, digest, 416, null, null)
        }
    }

    @Test
    fun queueIdentityIsStableAndSelectionScoped() {
        assertEquals(transferIdentity("p", "s", "i"), transferIdentity("p", "s", "i"))
        assertNotEquals(transferIdentity("p", "s", "i"), transferIdentity("p", "other", "i"))
        assertNotEquals(transferIdentity("p", "s", "i"), transferIdentity("other", "s", "i"))
    }
}

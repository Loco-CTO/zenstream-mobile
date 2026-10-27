package com.zenstream.zenstreammobile.offline

import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.CatalogException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCapabilityCompatibilityTest {
    @Test
    fun offlinePlaybackIsEnabledOutsideTheQaBuild() {
        assertTrue(OfflineRuntime.ENABLED)
    }

    @Test
    fun aDisabledOfflineContractIsRejected() {
        val capabilities = JSONObject().put("enabled", false)

        assertThrows(OfflineDownloadsUnsupportedException::class.java) {
            requireOfflineCapabilities(capabilities)
        }
    }

    @Test
    fun anUnsupportedContractVersionIsRejected() {
        val capabilities = JSONObject().put("enabled", true).put("contractVersion", "offline-v0")

        assertThrows(OfflineDownloadsUnsupportedException::class.java) {
            requireOfflineCapabilities(capabilities)
        }
    }

    @Test
    fun anOlderServerUsesTheClearUnavailableMessage() {
        val failure = CatalogException(404, "Not Found")

        assertEquals(
            R.string.offline_not_supported,
            offlineCapabilityErrorMessage(failure),
        )
    }

    @Test
    fun aValidContractStillRequiresItsServerIdentity() {
        requireOfflineCapabilities(
            JSONObject()
                .put("enabled", true)
                .put("contractVersion", "offline-v1")
                .put("serverInstanceId", "server")
        )
    }
}

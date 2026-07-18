package com.zenstream.zenstreammobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerUrlTest {
    @Test
    fun normalizesTrailingSlash() {
        assertEquals("https://example.com", normalizeServerUrl("  https://example.com/  "))
    }

    @Test
    fun permitsEmulatorLoopbackHttp() {
        assertEquals("http://10.0.2.2:9090", normalizeServerUrl("http://10.0.2.2:9090/"))
    }

    @Test
    fun rejectsNonTlsRemoteServer() {
        assertThrows(IllegalArgumentException::class.java) { normalizeServerUrl("http://example.com") }
    }

    @Test
    fun rejectsCredentialsAndFragments() {
        assertThrows(IllegalArgumentException::class.java) { normalizeServerUrl("https://user:pass@example.com") }
        assertThrows(IllegalArgumentException::class.java) { normalizeServerUrl("https://example.com/#private") }
    }
}

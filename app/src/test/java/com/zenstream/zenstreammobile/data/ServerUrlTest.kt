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
    fun permitsRemoteHttp() {
        assertEquals("http://example.com:9090", normalizeServerUrl("http://example.com:9090/"))
    }

    @Test
    fun rejectsUnsupportedSchemes() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeServerUrl("ftp://example.com")
        }
    }

    @Test
    fun rejectsCredentialsAndFragments() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeServerUrl("https://user:pass@example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            normalizeServerUrl("https://example.com/#private")
        }
    }
}

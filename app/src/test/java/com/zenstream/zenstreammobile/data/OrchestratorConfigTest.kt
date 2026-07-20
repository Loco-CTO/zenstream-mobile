package com.zenstream.zenstreammobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OrchestratorConfigTest {
    @Test
    fun acceptsTheProxyCapabilityResponse() {
        parseProxyConfig("{\"proxyVersion\":1}")
    }

    @Test
    fun rejectsAnOrchestratorWithoutTheProxy() {
        assertThrows(IllegalStateException::class.java) { parseProxyConfig("{}") }
    }

    @Test
    fun normalizesConfiguredJellyfinUrl() {
        assertEquals(
            "https://jellyfin.example",
            normalizeConfiguredJellyfinUrl(" https://jellyfin.example/ ")
        )
    }

    @Test
    fun rejectsMissingJellyfinUrl() {
        assertThrows(IllegalArgumentException::class.java) { normalizeConfiguredJellyfinUrl("http://remote.example") }
    }

    @Test
    fun normalizesOnlyFrontendLocales() {
        assertEquals("en", normalizeLocale("en"))
        assertEquals("ja", normalizeLocale("ja"))
        assertEquals("en", normalizeLocale("fr"))
    }
}

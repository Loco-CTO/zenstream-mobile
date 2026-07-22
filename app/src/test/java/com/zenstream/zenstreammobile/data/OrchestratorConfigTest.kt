package com.zenstream.zenstreammobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OrchestratorConfigTest {
    @Test
	fun acceptsTheCatalogCapabilityResponse() {
		parseProxyConfig("{\"catalog\":true}")
    }

    @Test
	fun rejectsAnOrchestratorWithoutTheCatalog() {
        assertThrows(IllegalStateException::class.java) { parseProxyConfig("{}") }
    }

    @Test
    fun normalizesConfiguredZenStreamUrl() {
        assertEquals(
            "https://orchestrator.example",
			normalizeServerUrl(" https://orchestrator.example/ ")
        )
    }

    @Test
    fun rejectsMissingZenStreamUrl() {
		assertThrows(IllegalArgumentException::class.java) { normalizeServerUrl("http://remote.example") }
    }

    @Test
    fun normalizesOnlyFrontendLocales() {
        assertEquals("en", normalizeLocale("en"))
        assertEquals("ja", normalizeLocale("ja"))
        assertEquals("en", normalizeLocale("fr"))
    }
}


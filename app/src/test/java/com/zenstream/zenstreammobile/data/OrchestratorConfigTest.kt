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
            normalizeServerUrl(" https://orchestrator.example/ "),
        )
    }

    @Test
    fun rejectsUnsupportedZenStreamUrl() {
        assertThrows(IllegalArgumentException::class.java) {
            normalizeServerUrl("ftp://remote.example")
        }
    }

    @Test
    fun normalizesOnlyFrontendLocales() {
        assertEquals("en", normalizeLocale("en"))
        assertEquals("ja", normalizeLocale("ja"))
        assertEquals("en", normalizeLocale("fr"))
    }

    @Test
    fun automaticInterfaceLocaleUsesTheFirstSupportedSystemLanguageFamily() {
        assertEquals(
            "ja",
            resolveInterfaceLocale(InterfaceLocaleMode.Automatic, listOf("fr-FR", "ja-JP")),
        )
        assertEquals(
            "en",
            resolveInterfaceLocale(InterfaceLocaleMode.Automatic, listOf("de-DE")),
        )
    }

    @Test
    fun explicitInterfaceLocaleIgnoresTheSystemAndInvalidStorageDefaultsToAutomatic() {
        assertEquals(
            "ja",
            resolveInterfaceLocale(InterfaceLocaleMode.Japanese, listOf("en-GB")),
        )
        assertEquals(InterfaceLocaleMode.Automatic, InterfaceLocaleMode.fromStorageValue("fr"))
    }
}

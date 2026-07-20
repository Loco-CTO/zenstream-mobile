package com.zenstream.zenstreammobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrchestratorConfigAndroidTest {
    @Test
    fun parsesProxyCapability() {
        parseProxyConfig("{\"proxyVersion\":1}")
    }

    @Test
    fun parsesAndNormalizesJellyfinUrl() {
        assertEquals(
            "https://jellyfin.example",
            parseMobileConfig("{\"jellyfinUrl\":\" https://jellyfin.example/ \"}")
        )
    }

    @Test
    fun rejectsMissingJellyfinUrl() {
        assertThrows(IllegalStateException::class.java) { parseMobileConfig("{}") }
    }

    @Test
    fun parsesOnlyFrontendLocales() {
        assertEquals("en", parseLocale("{\"locale\":\"en\"}"))
        assertEquals("ja", parseLocale("{\"locale\":\"ja\"}"))
        assertThrows(IllegalStateException::class.java) { parseLocale("{\"locale\":\"fr\"}") }
    }
}

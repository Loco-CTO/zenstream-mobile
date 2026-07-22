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
		parseProxyConfig("{\"catalog\":true}")
    }

    @Test
	fun normalizesOrchestratorUrl() {
        assertEquals(
            "https://orchestrator.example",
			normalizeServerUrl(" https://orchestrator.example/ ")
        )
    }

    @Test
	fun rejectsInsecureRemoteOrchestratorUrl() {
		assertThrows(IllegalArgumentException::class.java) { normalizeServerUrl("http://remote.example") }
    }

    @Test
    fun parsesOnlyFrontendLocales() {
        assertEquals("en", parseLocale("{\"locale\":\"en\"}"))
        assertEquals("ja", parseLocale("{\"locale\":\"ja\"}"))
        assertThrows(IllegalStateException::class.java) { parseLocale("{\"locale\":\"fr\"}") }
    }
}


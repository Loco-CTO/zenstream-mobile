package com.zenstream.zenstreammobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.SubtitleStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStoreTest {
    @Test
    fun persistsEncryptedSessionAndClearsIdentity() = runBlocking {
        val store = SessionStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
            dataStoreName = INSTRUMENTATION_SESSION_DATA_STORE_NAME,
        )
        store.clearAll()
        store.saveOrchestratorUrl("https://orchestrator.example")
        assertEquals("https://orchestrator.example", store.orchestratorUrl.first())
        store.saveServerConfig("https://orchestrator.example")
        store.saveSession(AuthSession("https://orchestrator.example", "secret-token", "user-1", "User"))
        assertEquals("secret-token", store.session.first()!!.token)
        store.clearSession()
        assertNull(store.session.first())
        assertEquals("https://orchestrator.example", store.serverUrl.first())
        assertEquals("https://orchestrator.example", store.orchestratorUrl.first())
        store.clearAll()
        assertNull(store.orchestratorUrl.first())
    }

    @Test
    fun subtitleStyleIsDeviceLocalAndSurvivesSessionClears() = runBlocking {
        val store = SessionStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
            dataStoreName = INSTRUMENTATION_SESSION_DATA_STORE_NAME,
        )
        store.clearAll()
        val style = SubtitleStyle(fontFamily = "mono", textScale = 140f)

        store.cacheSubtitleStyle(style)
        store.clearSession()
        assertEquals(style, store.cachedSubtitleStyle())
        store.clearAll()
        assertEquals(style, store.cachedSubtitleStyle())
    }
}


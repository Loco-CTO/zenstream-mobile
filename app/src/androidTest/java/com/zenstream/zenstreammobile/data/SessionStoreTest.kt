package com.zenstream.zenstreammobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.PlaybackTimeDisplayMode
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SubtitleStyle
import java.util.UUID
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
        val store =
            SessionStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
                dataStoreName = INSTRUMENTATION_SESSION_DATA_STORE_NAME,
            )
        store.clearAll()
        store.saveOrchestratorUrl("https://orchestrator.example")
        assertEquals("https://orchestrator.example", store.orchestratorUrl.first())
        store.saveServerConfig("https://orchestrator.example")
        store.saveSession(
            AuthSession(
                "https://orchestrator.example",
                "secret-token",
                "user-1",
                "User",
                avatarVersion = "avatar-v1",
            )
        )
        assertEquals("secret-token", store.session.first()!!.token)
        assertEquals("avatar-v1", store.session.first()!!.avatarVersion)
        store.clearSession()
        assertNull(store.session.first())
        assertEquals("https://orchestrator.example", store.serverUrl.first())
        assertEquals("https://orchestrator.example", store.orchestratorUrl.first())
        store.clearAll()
        assertNull(store.orchestratorUrl.first())
    }

    @Test
    fun subtitleStyleIsDeviceLocalAndSurvivesSessionClears() = runBlocking {
        val store =
            SessionStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
                dataStoreName = INSTRUMENTATION_SESSION_DATA_STORE_NAME,
            )
        store.clearAll()
        val style = SubtitleStyle(fontFamily = "mono", textScale = 140f)

        store.saveSession(AuthSession("https://server-one.example", "token-one", "user-1", "One"))
        store.cacheSubtitleStyle(style)
        store.clearSession()
        assertEquals(style, store.cachedSubtitleStyle())
        store.saveSession(AuthSession("https://server-two.example", "token-two", "user-2", "Two"))
        assertEquals(style, store.cachedSubtitleStyle())
        store.clearAll()
        assertEquals(style, store.cachedSubtitleStyle())
    }

    @Test
    fun interfaceLocaleModeIsDeviceLocalAndSurvivesAllClears() = runBlocking {
        val store =
            SessionStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
                dataStoreName = "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_locale",
                systemLanguageTags = { listOf("en-GB", "ja-JP") },
            )
        store.saveInterfaceLocaleMode(InterfaceLocaleMode.Automatic)
        assertEquals("en", store.locale.first())

        store.saveInterfaceLocaleMode(InterfaceLocaleMode.Japanese)
        store.clearSession()
        assertEquals(InterfaceLocaleMode.Japanese, store.interfaceLocaleMode.first())
        assertEquals("ja", store.locale.first())

        store.clearAll()
        assertEquals(InterfaceLocaleMode.Japanese, store.interfaceLocaleMode.first())
        store.saveInterfaceLocaleMode(InterfaceLocaleMode.Automatic)
    }

    @Test
    fun playerEngineDefaultsToMpvButPreservesAnExplicitMedia3Choice() = runBlocking {
        val store =
            SessionStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
                dataStoreName = "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_engine",
            )
        store.clearAll()
        store.savePlayerEngine(PlayerEngine.MPV)

        assertEquals(PlayerEngine.MPV, store.playerEngine.first())
        store.savePlayerEngine(PlayerEngine.MEDIA3)
        assertEquals(PlayerEngine.MEDIA3, store.playerEngine.first())

        store.clearSession()
        assertEquals(PlayerEngine.MEDIA3, store.playerEngine.first())
        store.clearAll()
        assertEquals(PlayerEngine.MEDIA3, store.playerEngine.first())
        store.savePlayerEngine(PlayerEngine.MPV)
    }

    @Test
    fun playbackTimeDisplayModeDefaultsToRemainingAndSurvivesSessionClears() = runBlocking {
        val store =
            SessionStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
                dataStoreName =
                    "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_time_display_${UUID.randomUUID()}",
            )

        assertEquals(PlaybackTimeDisplayMode.Remaining, store.playbackTimeDisplayMode.first())
        store.savePlaybackTimeDisplayMode(PlaybackTimeDisplayMode.Elapsed)
        assertEquals(PlaybackTimeDisplayMode.Elapsed, store.playbackTimeDisplayMode.first())

        store.clearSession()
        assertEquals(PlaybackTimeDisplayMode.Elapsed, store.playbackTimeDisplayMode.first())
        store.clearAll()
        assertEquals(PlaybackTimeDisplayMode.Elapsed, store.playbackTimeDisplayMode.first())
    }
}

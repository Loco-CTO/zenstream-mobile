package com.zenstream.zenstreammobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MpvVideoOutput
import com.zenstream.zenstreammobile.model.MpvVideoProfile
import com.zenstream.zenstreammobile.model.MpvVideoScaler
import com.zenstream.zenstreammobile.model.PlaybackTimeDisplayMode
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SubtitleStyle
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionStoreTest {
    @Test
    fun firstSessionReadWaitsForInitialDataStoreLoad() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_cold_${UUID.randomUUID()}"
        val store = SessionStore(context, dataStoreName = name)
        val savedSession =
            AuthSession(
                "https://orchestrator.example",
                "cold-access-token",
                "user-1",
                "User",
            )
        store.saveSession(savedSession)

        val recreatedStore = SessionStore(context, dataStoreName = name)
        assertEquals(savedSession, recreatedStore.session.first())

        val loggedOutName =
            "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_logged_out_${UUID.randomUUID()}"
        val loggedOutStore = SessionStore(context, dataStoreName = loggedOutName)
        assertEquals(
            StoredSessionState.Loaded(null),
            loggedOutStore.sessionState.first { it !is StoredSessionState.Loading },
        )
        assertNull(loggedOutStore.session.first())

        store.clearAll()
        loggedOutStore.clearAll()
    }

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
                artworkTicket = "artwork-ticket",
            )
        )
        assertEquals("secret-token", store.session.first()!!.token)
        assertEquals("avatar-v1", store.session.first()!!.avatarVersion)
        assertEquals("artwork-ticket", store.session.first()!!.artworkTicket)
        store.clearSession()
        assertNull(store.session.first())
        assertEquals("https://orchestrator.example", store.serverUrl.first())
        assertEquals("https://orchestrator.example", store.orchestratorUrl.first())
        store.clearAll()
        assertNull(store.orchestratorUrl.first())
    }

    @Test
    fun pendingRefreshAttemptSurvivesStoreRecreationAndRotationClearsItAtomically() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_refresh_${UUID.randomUUID()}"
        val store = SessionStore(context, dataStoreName = name)
        val original =
            AuthSession(
                "https://orchestrator.example",
                "access-before",
                "user-1",
                "User",
                refreshToken = "refresh-before",
                accessExpiresAtMillis = 1L,
                refreshExpiresAtMillis = System.currentTimeMillis() + 60_000,
            )
        val attemptId = UUID.randomUUID().toString()
        store.saveSession(original)

        val pending = store.beginRefreshAttempt(original, attemptId)!!
        assertEquals(attemptId, store.session.first()?.refreshAttemptId)

        // A newly constructed store reads the same durable DataStore file, as a cold process does.
        val recreatedStore = SessionStore(context, dataStoreName = name)
        val restored = recreatedStore.session.first()!!
        assertEquals(attemptId, restored.refreshAttemptId)
        assertEquals("refresh-before", restored.refreshToken)

        val rotated =
            restored.copy(
                token = "access-after",
                refreshToken = "refresh-after",
                accessExpiresAtMillis = System.currentTimeMillis() + 900_000,
                refreshExpiresAtMillis = System.currentTimeMillis() + 86_400_000,
                refreshAttemptId = null,
            )
        assertTrue(recreatedStore.saveRefreshedSessionIfCurrent(pending, rotated))

        val saved = store.session.first()!!
        assertEquals("access-after", saved.token)
        assertEquals("refresh-after", saved.refreshToken)
        assertNull(saved.refreshAttemptId)
        assertFalse(store.clearSessionIfCurrent(pending, "stale_refresh_rejection"))
        assertEquals("access-after", store.session.first()?.token)
        store.clearAll()
    }

    @Test
    fun staleClearCannotDeleteSessionWithNewPendingAttempt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store =
            SessionStore(
                context,
                dataStoreName =
                    "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_refresh_cas_${UUID.randomUUID()}",
            )
        val original =
            AuthSession(
                "https://orchestrator.example",
                "access-before",
                "user-1",
                "User",
                refreshToken = "refresh-before",
            )
        store.saveSession(original)
        val pending = store.beginRefreshAttempt(original, UUID.randomUUID().toString())!!

        assertFalse(store.clearSessionIfCurrent(original, "stale_401"))
        assertEquals(pending.refreshAttemptId, store.session.first()?.refreshAttemptId)
        assertTrue(store.clearSessionIfCurrent(pending, "refresh_rejected"))
        assertNull(store.session.first())
        store.clearAll()
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
                dataStoreName =
                    "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_engine_${UUID.randomUUID()}",
            )
        store.clearAll()
        assertEquals(PlayerEngine.MPV, store.playerEngine.first())
        assertEquals(MpvVideoOutput.GPU, store.mpvVideoOutput.first())
        assertEquals(MpvVideoProfile.FAST, store.mpvVideoProfile.first())
        assertEquals(MpvVideoScaler.BILINEAR, store.mpvVideoScaler.first())
        store.savePlayerEngine(PlayerEngine.MPV)

        assertEquals(PlayerEngine.MPV, store.playerEngine.first())
        store.saveMpvVideoOutput(MpvVideoOutput.GPU_NEXT)
        store.saveMpvVideoProfile(MpvVideoProfile.GPU_HQ)
        store.saveMpvVideoScaler(MpvVideoScaler.LANCZOS)
        assertEquals(MpvVideoOutput.GPU_NEXT, store.mpvVideoOutput.first())
        assertEquals(MpvVideoProfile.GPU_HQ, store.mpvVideoProfile.first())
        assertEquals(MpvVideoScaler.LANCZOS, store.mpvVideoScaler.first())
        store.savePlayerEngine(PlayerEngine.MEDIA3)
        assertEquals(PlayerEngine.MEDIA3, store.playerEngine.first())

        store.clearSession()
        assertEquals(PlayerEngine.MEDIA3, store.playerEngine.first())
        assertEquals(MpvVideoOutput.GPU_NEXT, store.mpvVideoOutput.first())
        assertEquals(MpvVideoProfile.GPU_HQ, store.mpvVideoProfile.first())
        assertEquals(MpvVideoScaler.LANCZOS, store.mpvVideoScaler.first())
        store.clearAll()
        assertEquals(PlayerEngine.MEDIA3, store.playerEngine.first())
        assertEquals(MpvVideoOutput.GPU_NEXT, store.mpvVideoOutput.first())
        assertEquals(MpvVideoProfile.GPU_HQ, store.mpvVideoProfile.first())
        assertEquals(MpvVideoScaler.LANCZOS, store.mpvVideoScaler.first())
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

    @Test
    fun syncplayPresenceSequenceIsMonotonicAndDeviceLocal() = runBlocking {
        val store =
            SessionStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
                dataStoreName =
                    "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_presence_${UUID.randomUUID()}",
            )

        assertEquals(0L, store.syncplayPresenceSequence())
        store.recordSyncplayPresenceSequence(7L)
        store.recordSyncplayPresenceSequence(3L)
        assertEquals(7L, store.syncplayPresenceSequence())

        store.clearSession()
        assertEquals(7L, store.syncplayPresenceSequence())
        store.clearAll()
        assertEquals(7L, store.syncplayPresenceSequence())
    }
}

package com.zenstream.zenstreammobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.model.AuthSession
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Optional companion to the web live test and the loopback recovery lab. */
@RunWith(AndroidJUnit4::class)
class SyncplayLiveRecoveryTest {
    @Test
    fun recoversInTheOriginalMixedClientRoom() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("syncplayLabUrl")
        assumeTrue("Start the isolated SyncPlay lab and web live test", url != null)
        val serverUrl = requireNotNull(url).trimEnd('/')
        val store =
            SessionStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
                dataStoreName =
                    "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_live_${UUID.randomUUID()}",
            )
        val manager =
            SyncplayManager(AuthSession(serverUrl, "lab-mobile", "lab-mobile", "Android"), store)
        val client = OkHttpClient()
        suspend fun labState(): JSONObject =
            withContext(Dispatchers.IO) {
                client
                    .newCall(Request.Builder().url("$serverUrl/__test/state").build())
                    .execute()
                    .use {
                        JSONObject(it.body.string())
                    }
            }
        val reporter = launch {
            var lastKey = ""
            manager.state.collect { state ->
                val group = state.active ?: return@collect
                if (group.itemId == null) return@collect
                val key =
                    "${state.recoveryEpoch}:${group.mediaGeneration}:${group.timelineRevision}"
                if (key != lastKey) {
                    lastKey = key
                    // A ready engine stays mounted: recovery must solicit this fresh probe.
                    manager.reportPresence(true, false, immediate = true)
                }
            }
        }
        try {
            withTimeout(90_000) {
                while (manager.state.value.groups.isEmpty()) delay(50)
                val group = manager.state.value.groups.first()
                manager.join(group.id)
                val originalParticipant = manager.state.value.participantId
                while (labState().optString("phase") != "background") delay(100)
                assertEquals(group.id, manager.state.value.active?.id)
                assertEquals(originalParticipant, manager.state.value.participantId)
                assertTrue(manager.state.value.connected)
                manager.reportPresence(false, false, immediate = true, pauseRoom = true)
                while (labState().optString("phase") != "done") delay(100)
                assertEquals(group.id, manager.state.value.active?.id)
                assertTrue(manager.state.value.active!!.playing)
            }
        } finally {
            reporter.cancelAndJoin()
            manager.stop()
            store.clearAll()
        }
    }
}

package com.zenstream.zenstreammobile.audio

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSpec
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.offline.OfflineNetworkPolicy
import com.zenstream.zenstreammobile.offline.OfflinePolicyException
import com.zenstream.zenstreammobile.offline.OfflineRuntime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

@UnstableApi
class OfflineAudioDataSourceTest {
    @Test
    fun offlinePolicyAllowsLocalReadsAndStopsOpenOrActiveHttpReads() = runBlocking {
        OfflineRuntime.get(InstrumentationRegistry.getInstrumentation().targetContext)
            .connection
            .state
            .first { it.initialized }
        val previous = OfflineNetworkPolicy.allowed
        try {
            OfflineNetworkPolicy.publish(false)
            val local = OfflineAudioDataSource(ByteArrayDataSource(byteArrayOf(1, 2, 3)))
            local.open(DataSpec(Uri.parse("file:///app-owned-test-file")))
            assertEquals(3, local.read(ByteArray(3), 0, 3))
            local.close()
            val remote = OfflineAudioDataSource(ByteArrayDataSource(byteArrayOf(1, 2, 3)))
            assertThrows(OfflinePolicyException::class.java) {
                remote.open(DataSpec(Uri.parse("https://example.invalid/media")))
            }
            OfflineNetworkPolicy.publish(true)
            remote.open(DataSpec(Uri.parse("https://example.invalid/media")))
            OfflineNetworkPolicy.publish(false)
            assertThrows(OfflinePolicyException::class.java) { remote.read(ByteArray(3), 0, 3) }
            remote.close()
        } finally {
            OfflineNetworkPolicy.publish(previous)
        }
    }
}

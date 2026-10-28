package com.zenstream.zenstreammobile.offline

import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class OfflineNetworkPolicyTest {
    @After
    fun restorePolicy() {
        OfflineNetworkPolicy.publish(true)
    }

    @Test
    fun explicitOfflineMakesNoHttpRequest() {
        MockWebServer().use { server ->
            server.start()
            val client = OfflineNetworkPolicy.client()
            OfflineNetworkPolicy.publish(false)
            assertThrows(OfflinePolicyException::class.java) {
                client.newCall(Request.Builder().url(server.url("/api/config")).build()).execute()
            }
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun automaticModeDoesNotRequirePublicInternet() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            OfflineNetworkPolicy.publish(true)
            OfflineNetworkPolicy.client()
                .newCall(Request.Builder().url(server.url("/api/config")).build())
                .execute()
                .use { assertEquals(200, it.code) }
            assertEquals("/api/config", server.takeRequest().path)
        }
    }
}

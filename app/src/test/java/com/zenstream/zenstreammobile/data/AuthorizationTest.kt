package com.zenstream.zenstreammobile.data

import android.os.Build
import com.zenstream.zenstreammobile.BuildConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationTest {
    @Test
    fun anonymousHeaderContainsMobileIdentity() {
        val header = JellyfinApi.authorizationHeader(null, "device-1")
        assertTrue(header.startsWith("MediaBrowser "))
        assertTrue(header.contains("Client=\"ZenStream\""))
        assertEquals(
            Build.MODEL?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", true) }
                ?: "Android",
            Regex("Device=\\\"([^\\\"]+)\\\"").find(header)?.groupValues?.get(1),
        )
        assertTrue(header.contains("DeviceId=\"device-1\""))
        assertTrue(header.contains("Version=\"${BuildConfig.ZENSTREAM_VERSION}\""))
        assertFalse(header.contains("Version=\"1.0\""))
        assertFalse(header.contains("Token=\""))
    }

    @Test
    fun authenticatedHeaderContainsToken() {
        assertTrue(
            JellyfinApi.authorizationHeader("secret", "device-1").contains("Token=\"secret\"")
        )
    }

}

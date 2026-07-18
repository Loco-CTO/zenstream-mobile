package com.zenstream.zenstreammobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationTest {
    @Test
    fun anonymousHeaderContainsMobileIdentity() {
        val header = JellyfinApi.authorizationHeader(null, "device-1")
        assertTrue(header.startsWith("MediaBrowser "))
        assertTrue(header.contains("Client=\"ZenStream Mobile\""))
        assertTrue(header.contains("DeviceId=\"device-1\""))
        assertFalse(header.contains("Token=\""))
    }

    @Test
    fun authenticatedHeaderContainsToken() {
        assertTrue(
            JellyfinApi.authorizationHeader("secret", "device-1").contains("Token=\"secret\"")
        )
    }

}

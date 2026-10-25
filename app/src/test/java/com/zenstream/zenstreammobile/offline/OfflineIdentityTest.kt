package com.zenstream.zenstreammobile.offline

import org.junit.Assert.*
import org.junit.Test

class OfflineIdentityTest {
    @Test
    fun profileIdentitySeparatesAccountsAndRecreatedServers() {
        val first = OfflineProfile.identity("server-a", "account-a")
        assertEquals(first, OfflineProfile.identity("server-a", "account-a"))
        assertNotEquals(first, OfflineProfile.identity("server-b", "account-a"))
        assertNotEquals(first, OfflineProfile.identity("server-a", "account-b"))
        assertNotEquals(OfflineProfile.identity("ab", "c"), OfflineProfile.identity("a", "bc"))
        assertEquals(64, first.length)
    }
}

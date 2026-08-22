package com.zenstream.zenstreammobile.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NotificationsScreenTest {
    @Test
    fun formatsTheOffsetTimestampReturnedByTheNotificationApi() {
        val raw = "2026-08-20T23:30:39.090571+00:00"

        val formatted = formatNotificationDateTime(raw)

        assertNotEquals(raw, formatted)
        assertFalse(formatted.contains("T"))
        assertFalse(formatted.contains("+00:00"))
    }
}

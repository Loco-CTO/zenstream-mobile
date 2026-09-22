package com.zenstream.zenstreammobile.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioNotificationConfigTest {
    @Test
    fun preservesTheForegroundNotificationIdentity() {
        assertEquals("audio_playback", AudioNotificationConfig.CHANNEL_ID)
        assertEquals(21_847, AudioNotificationConfig.NOTIFICATION_ID)
    }
}

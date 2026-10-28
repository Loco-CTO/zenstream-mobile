package com.zenstream.zenstreammobile.ui.player

import androidx.media3.common.C
import java.io.File
import java.net.URI
import org.junit.Assert.*
import org.junit.Test

class LocalDecoderRecoveryTest {
    @Test
    fun independentRecoveryIsBoundedAndScopedToTheActualSource() {
        val policy = LocalDecoderRecovery()
        policy.source("file:one")
        assertTrue(policy.attempt(C.TRACK_TYPE_AUDIO, false))
        assertTrue(policy.softwareFor("audio/aac"))
        assertFalse(policy.softwareFor("video/av01"))
        assertFalse(policy.attempt(C.TRACK_TYPE_AUDIO, false))
        assertTrue(policy.attempt(C.TRACK_TYPE_VIDEO, false))
        policy.source("file:one")
        assertFalse(policy.attempt(C.TRACK_TYPE_VIDEO, false))
        policy.source("file:two")
        assertTrue(policy.attempt(C.TRACK_TYPE_VIDEO, false))
    }

    @Test
    fun recognizedHdrNeverEntersVideoSoftwareRecoveryButAudioCanRecover() {
        val policy = LocalDecoderRecovery()
        policy.source("file:hdr")
        assertFalse(policy.attempt(C.TRACK_TYPE_VIDEO, true))
        assertFalse(policy.softwareVideo)
        assertTrue(policy.attempt(C.TRACK_TYPE_AUDIO, true))
    }

    @Test
    fun mpvReceivesLocalFilePathsInsteadOfFileUris() {
        val source = File("/offline/media item.mp4").toURI().toASCIIString()

        assertEquals(File(URI(source)).absolutePath, mpvPlaybackPath(source))
        assertEquals(
            "https://example.invalid/media",
            mpvPlaybackPath("https://example.invalid/media"),
        )
    }
}

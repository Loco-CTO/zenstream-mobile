package com.zenstream.zenstreammobile.ui.player

import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3RecoveryPolicyTest {
    @Test
    fun failuresRemainSpecificRatherThanTreatingEveryErrorAsAVideoDecoderProblem() {
        assertEquals(
            Media3FailureKind.CONTAINER,
            classifyMedia3Failure(
                androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            ),
        )
        assertEquals(
            Media3FailureKind.DECODER,
            classifyMedia3Failure(
                androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            ),
        )
        assertEquals(
            Media3FailureKind.NETWORK,
            classifyMedia3Failure(
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            ),
        )
        assertEquals(
            Media3FailureKind.AUTHENTICATION,
            classifyMedia3Failure(
                androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION
            ),
        )
    }

    @Test
    fun audioAndVideoHaveIndependentBoundedAttempts() {
        val recovery = Media3LocalRecovery()
        assertTrue(recovery.trySoftware(true, true, false, true, false))
        assertFalse(recovery.trySoftware(true, true, false, true, false))
        assertTrue(recovery.trySoftware(false, true, false, true, false))
        assertFalse(recovery.trySoftware(false, true, false, true, false))
        recovery.resetAudio()
        assertTrue(recovery.trySoftware(false, true, false, true, false))
        assertFalse(recovery.trySoftware(true, true, false, true, false))
    }

    @Test
    fun explicitModesUnavailableExtensionsAndHdrDoNotTriggerVideoSoftwareRetry() {
        assertFalse(Media3LocalRecovery().trySoftware(true, true, false, false, false))
        assertFalse(Media3LocalRecovery().trySoftware(true, false, false, true, false))
        assertFalse(Media3LocalRecovery().trySoftware(true, true, true, true, false))
        assertFalse(Media3LocalRecovery().trySoftware(true, true, false, true, true))
        assertTrue(Media3LocalRecovery().trySoftware(false, true, false, true, true))
    }

    @Test
    fun localSourcesNeverRequestServerConversion() {
        val recovery = Media3ServerRecovery()
        for (kind in Media3FailureKind.entries) {
            for (audio in listOf(false, true)) {
                assertNull(recovery.nextMode("download", "local", audio, kind, false))
            }
        }
        assertEquals(
            "video-transcode",
            recovery.nextMode("download", "direct", false, Media3FailureKind.DECODER, false),
        )
    }

    @Test
    fun serverRecoveryAdvancesWithoutRepeatingOrDowngrading() {
        val recovery = Media3ServerRecovery()
        fun next(mode: String, audio: Boolean) =
            recovery.nextMode("source", mode, audio, Media3FailureKind.DECODER, false)
        assertEquals("audio-transcode", next("direct", true))
        assertNull(next("direct", true))
        assertNull(next("audio-transcode", true))
        assertEquals("video-transcode", next("audio-transcode", false))
        assertNull(next("video-transcode", false))
        assertNull(next("video-transcode", true))
    }

    @Test
    fun networkAuthAndHdrAreNotBlindlyVideoTranscoded() {
        val recovery = Media3ServerRecovery()
        for (kind in
            listOf(
                Media3FailureKind.NETWORK,
                Media3FailureKind.AUTHENTICATION,
                Media3FailureKind.OTHER,
                Media3FailureKind.HDR_UNSUPPORTED,
            )) {
            assertNull(recovery.nextMode("s", "direct", false, kind, false))
        }
        assertNull(recovery.nextMode("s", "direct", false, Media3FailureKind.DECODER, true))
        assertEquals(
            "audio-transcode",
            recovery.nextMode("s", "direct", true, Media3FailureKind.DECODER, true),
        )
    }

    @Test
    fun changingSourceStartsFreshServerAttempts() {
        val recovery = Media3ServerRecovery()
        assertEquals(
            "video-transcode",
            recovery.nextMode("first", "direct", false, Media3FailureKind.CONTAINER, false),
        )
        assertEquals(
            "video-transcode",
            recovery.nextMode("second", "direct", false, Media3FailureKind.CONTAINER, false),
        )
    }

    @Test
    fun startupWatchdogRequiresProgressAndResetsAcrossIneligibleTime() {
        val watchdog = VideoFrameWatchdog()
        assertFalse(watchdog.sample(0, true, 0, 0, 0, false))
        assertFalse(watchdog.sample(9_000, true, 0, 0, 0, false))
        assertFalse(watchdog.sample(10_000, false, 0, 0, 0, false))
        assertFalse(watchdog.sample(30_000, true, 0, 0, 0, false))
        assertFalse(watchdog.sample(37_999, true, 2_000, 2, 0, false))
        assertTrue(watchdog.sample(38_000, true, 2_001, 3, 0, false))
        assertFalse(watchdog.sample(39_000, true, 3_000, 4, 0, false))
    }

    @Test
    fun stallDetectionRequiresNewDecoderInputsWithoutRenderedFrames() {
        val watchdog = VideoFrameWatchdog()
        assertFalse(watchdog.sample(0, true, 0, 5, 5, true))
        assertFalse(watchdog.sample(11_000, true, 11_000, 5, 5, true))
        assertFalse(watchdog.sample(12_000, true, 12_000, 6, 6, true))
        assertFalse(watchdog.sample(21_999, true, 21_999, 10, 6, true))
        assertTrue(watchdog.sample(22_000, true, 22_000, 11, 6, true))
    }
}

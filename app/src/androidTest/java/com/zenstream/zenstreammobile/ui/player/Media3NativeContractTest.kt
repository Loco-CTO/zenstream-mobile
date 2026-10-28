package com.zenstream.zenstreammobile.ui.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.data.Media3NativeDecoders
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import `is`.xyz.mpv.MPVLib
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3NativeContractTest {
    @Test fun mpvThenMedia3() = coexist(mpvFirst = true)

    @Test fun media3ThenMpv() = coexist(mpvFirst = false)

    @Test
    fun configuredDecodersAreActuallyRegistered() {
        assertTrue(Media3NativeDecoders.available)
        for (mime in
            listOf(
                "video/avc",
                "video/hevc",
                "video/x-vnd.on2.vp8",
                "video/x-vnd.on2.vp9",
                "video/av01",
                "video/mpeg2",
                "video/mp4v-es",
                "video/mp42",
                "video/mp43",
                "video/wvc1",
                "video/mjpeg",
                "audio/ac3",
                "audio/eac3",
                "audio/vnd.dts",
                "audio/true-hd",
                "audio/flac",
                "audio/alac",
            )) {
            assertTrue("Decoder missing for $mime", FfmpegLibrary.supportsFormat(mime))
        }
    }

    private fun coexist(mpvFirst: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (!mpvFirst) assertTrue(Media3NativeDecoders.available)
        instrumentation.runOnMainSync {
            MPVLib.create(instrumentation.targetContext)
            try {
                MPVLib.setOptionString("vo", "null")
                MPVLib.setOptionString("ao", "null")
                MPVLib.init()
                assertNotNull(MPVLib.getPropertyString("mpv-version"))
                assertNotNull(MPVLib.getPropertyString("ffmpeg-version"))
                assertTrue(Media3NativeDecoders.available)
                assertEquals(
                    (63 shl 16) or (1 shl 8) or 101,
                    FfmpegLibrary.getRuntimeCodecVersion(),
                )
            } finally {
                MPVLib.destroy()
            }
        }
    }
}

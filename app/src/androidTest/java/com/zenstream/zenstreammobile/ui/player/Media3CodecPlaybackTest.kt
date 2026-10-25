package com.zenstream.zenstreammobile.ui.player

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3CodecPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var captureName = "fixture"
    private var captureIndex = 0

    @Test fun h264() = play("h264.mp4")

    @Test fun h264High10() = play("h264-high10.mkv")

    @Test fun hevcMain() = play("hevc-main.mkv")

    @Test fun hevcMain10() = play("hevc-main10.mp4")

    @Test fun vp8() = play("vp8.webm")

    @Test fun vp9() = play("vp9.webm")

    @Test fun av1() = play("av1.mp4")

    @Test fun mpeg2ProgramStream() = play("mpeg2.mpg")

    @Test fun mpeg2TransportStream() = play("mpeg2.ts")

    @Test fun mpeg4() = play("mpeg4.avi")

    @Test fun microsoftMpeg4v2() = play("msmpeg4v2.avi")

    @Test fun microsoftMpeg4v3() = play("msmpeg4v3.avi")

    @Test fun motionJpeg() = play("mjpeg.avi")

    @Test fun ac3Audio() = play("ac3.mkv")

    @Test fun eac3Audio() = play("eac3.mkv")

    @Test fun dtsAudio() = play("dts.mkv")

    @Test fun trueHdAudio() = play("truehd.mkv")

    @Test
    fun decoderInitializationFailureRecoversLocally() = play("h264.mp4", forceInitFailure = true)

    private fun play(name: String, forceInitFailure: Boolean = false) {
        captureName = name
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = instrumentation.targetContext.cacheDir.resolve(name)
        instrumentation.context.assets.open("media3/$name").use { input ->
            file.outputStream().use(input::copyTo)
        }
        val selector =
            if (forceInitFailure)
                MediaCodecSelector { mime, secure, tunnel ->
                    val standard = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunnel)
                    if (mime.startsWith("video/") && standard.isNotEmpty())
                        listOf(
                            MediaCodecInfo.newInstance(
                                "zenstream.test.missing.decoder",
                                mime,
                                mime,
                                standard.first().capabilities,
                                true,
                                false,
                                true,
                                false,
                                false,
                            )
                        )
                    else standard
                }
            else MediaCodecSelector.DEFAULT
        val engine = Media3PlaybackEngine(selector)
        lateinit var view: PlayerView
        try {
            compose.setContent {
                AndroidView(factory = { engine.createView(it).also { view = it as PlayerView } })
            }
            compose.runOnIdle {
                engine.configureSource(
                    Media3SourceContext(name, expectedVideo = true, mode = "direct")
                )
                if (!forceInitFailure)
                    engine.selectVideoDecoder(VideoDecoderPreference.APP_SOFTWARE)
                engine.prepare(file.toURI().toString(), 0.0)
            }
            compose.waitUntil(20_000) {
                engine.state.value.media3?.firstFrameRendered == true ||
                    engine.state.value.error != null
            }
            assertNull("$name: ${engine.state.value}", engine.state.value.error)
            assertTrue(
                "$name decoder=${engine.state.value.media3?.videoDecoder}",
                engine.state.value.media3?.videoDecoder?.startsWith("ffmpeg") == true,
            )
            assertEquals((63 shl 16) or (1 shl 8) or 101, FfmpegLibrary.getRuntimeCodecVersion())
            if (forceInitFailure) assertEquals(1, engine.state.value.media3?.videoSoftwareRetries)
            val first = pixels(view.videoSurfaceView as SurfaceView)
            SystemClock.sleep(700)
            val second = pixels(view.videoSurfaceView as SurfaceView)
            assertFalse("$name must display changing video pixels", first.contentEquals(second))
            compose.runOnIdle {
                engine.pause()
                engine.seekTo(4.0)
            }
            compose.waitUntil(8_000) {
                kotlin.math.abs(engine.state.value.positionSeconds - 4.0) < .3
            }
            assertNull(engine.state.value.error)
            var sought = pixels(view.videoSurfaceView as SurfaceView)
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (second.contentEquals(sought) && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
                sought = pixels(view.videoSurfaceView as SurfaceView)
            }
            assertFalse("$name seek must display another frame", second.contentEquals(sought))
        } finally {
            compose.runOnIdle { engine.release() }
            file.delete()
        }
    }

    private fun pixels(surface: SurfaceView): IntArray {
        val bitmap = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = -1
        PixelCopy.request(
            surface,
            bitmap,
            {
                result = it
                latch.countDown()
            },
            Handler(Looper.getMainLooper()),
        )
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(PixelCopy.SUCCESS, result)
        val pixels = IntArray(160 * 90)
        bitmap.getPixels(pixels, 0, 160, 0, 0, 160, 90)
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getExternalFilesDir("media3-qa")
            ?.let { folder ->
                folder.mkdirs()
                folder.resolve("$captureName-${captureIndex++}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        bitmap.recycle()
        assertTrue(
            "Expected colored fixture pixels",
            pixels.any { Color.red(it) > 80 && Color.green(it) < 80 },
        )
        return pixels
    }
}

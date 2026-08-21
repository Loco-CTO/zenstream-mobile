package com.zenstream.zenstreammobile.ui.player

import android.view.View
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerEngineTest {
    @Test
    fun media3PlayerViewHidesNativeSubtitleRendering() {
        val engine = Media3PlaybackEngine()
        var playerView: PlayerView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            playerView =
                engine.createView(InstrumentationRegistry.getInstrumentation().targetContext)
                    as PlayerView
        }

        assertNotNull(playerView?.subtitleView)
        assertEquals(View.GONE, playerView?.subtitleView?.visibility)
        engine.release()
    }

    @Test
    fun media3ActivePlaybackClearsStaleErrors() {
        val failed = EngineState(error = "old playback failed")

        assertNull(media3PlaybackState(failed, Player.STATE_BUFFERING).error)
        assertNull(media3PlaybackState(failed, Player.STATE_READY).error)
    }

    @Test
    fun media3InactivePlaybackStatePreservesErrors() {
        val failed = EngineState(error = "active playback failed")

        assertEquals("active playback failed", media3PlaybackState(failed, Player.STATE_IDLE).error)
        assertEquals("active playback failed", media3PlaybackState(failed, Player.STATE_ENDED).error)
    }

    @Test
    fun mpvCaptionOptionsDisableNativeSubtitleTracks() {
        assertEquals(
            mapOf("sub-auto" to "no", "sid" to "no", "secondary-sid" to "no"),
            mpvCaptionOptions,
        )
    }

    @Test
    fun releasedMpvEngineDoesNotTouchNativeState() {
        val engine = MpvPlaybackEngine(InstrumentationRegistry.getInstrumentation().targetContext)

        engine.release()
        engine.release()
        engine.play()
        engine.pause()
        engine.seekTo(10.0)
        engine.setSpeed(1.25f)

        assertEquals(0.0, engine.currentPositionSeconds(), 0.0)
    }

    @Test
    fun mpvSurfaceReleaseWaitsForSurfaceTeardown() {
        val lifecycle = MpvSurfaceLifecycle()

        lifecycle.markSurfaceCreated()
        assertFalse(lifecycle.requestDestroy())
        assertTrue(lifecycle.markSurfaceDestroyed())
        assertTrue(lifecycle.markDestroyed())
        assertFalse(lifecycle.markDestroyed())
    }

    @Test
    fun mpvSurfaceReleaseDestroysImmediatelyWithoutSurface() {
        val lifecycle = MpvSurfaceLifecycle()

        assertTrue(lifecycle.requestDestroy())
        assertTrue(lifecycle.markDestroyed())
        assertFalse(lifecycle.canUseSurface())
    }
}

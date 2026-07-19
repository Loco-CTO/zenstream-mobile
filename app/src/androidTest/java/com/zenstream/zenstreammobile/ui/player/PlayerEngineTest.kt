package com.zenstream.zenstreammobile.ui.player

import android.view.View
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerEngineTest {
    @Test
    fun media3PlayerViewHidesNativeSubtitleRendering() {
        val engine = Media3PlaybackEngine()
        var playerView: PlayerView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            playerView = engine.createView(InstrumentationRegistry.getInstrumentation().targetContext) as PlayerView
        }

        assertNotNull(playerView?.subtitleView)
        assertEquals(View.GONE, playerView?.subtitleView?.visibility)
        engine.release()
    }

    @Test
    fun mpvCaptionOptionsDisableNativeSubtitleTracks() {
        assertEquals(
            mapOf("sub-auto" to "no", "sid" to "no", "secondary-sid" to "no"),
            mpvCaptionOptions,
        )
    }
}

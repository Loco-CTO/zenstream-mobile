package com.zenstream.zenstreammobile

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.app.PictureInPictureParams
import android.util.Rational
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.model.AuthSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackActivityTest {
    private lateinit var sessionStore: SessionStore

    @Before
    fun setUpSession() = runBlocking {
        sessionStore = SessionStore(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        )
        sessionStore.saveServerConfig("https://orchestrator.example", "https://jellyfin.example")
        sessionStore.saveSession(AuthSession("https://jellyfin.example", "test-token", "user-1", "Test"))
    }

    @After
    fun clearSession() = runBlocking {
        sessionStore.clearAll()
    }

    @Test
    fun playbackActivityLocksLandscapeAndImmersiveBehavior() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivityContract.EXTRA_ITEM_ID, "item-1")
            putExtra(PlaybackActivityContract.EXTRA_ITEM_NAME, "Example")
        }

        ActivityScenario.launch<PlaybackActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, activity.requestedOrientation)
                assertEquals(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
                    WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                        .systemBarsBehavior,
                )
            }
        }
    }

    @Test
    fun playbackActivityCanEnterPictureInPicture() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageManager.hasSystemFeature("android.software.picture_in_picture"))
        val intent = Intent(context, PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivityContract.EXTRA_ITEM_ID, "item-1")
            putExtra(PlaybackActivityContract.EXTRA_ITEM_NAME, "Example")
        }

        ActivityScenario.launch<PlaybackActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(
                    activity.enterPictureInPictureMode(
                        PictureInPictureParams.Builder()
                            .setAspectRatio(Rational(16, 9))
                            .build(),
                    ),
                )
                assertTrue(activity.isInPictureInPictureMode)
            }
        }
    }
}

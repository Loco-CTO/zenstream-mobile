package com.zenstream.zenstreammobile.ui.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class VideoMiniPlayerTest {
    @get:Rule val compose = createComposeRule()
    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun resetPlacement() {
        context
            .getSharedPreferences("video_miniplayer", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun render(
        playing: Boolean = true,
        canControl: Boolean = true,
        onToggle: () -> Unit = {},
        onClose: () -> Unit = {},
        onExpand: () -> Unit = {},
    ) {
        compose.setContent {
            ZenStreamTheme {
                Box(
                    Modifier.fillMaxSize().background(Color(0xFF111114)).testTag("browse-surface")
                ) {
                    VideoMiniPlayerFrame {
                        Box(Modifier.fillMaxSize().background(Color(0xFF34304B))) {
                            MiniVideoControls(
                                playing,
                                canControl,
                                30.0,
                                120.0,
                                onToggle,
                                onClose,
                                onExpand,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun controlsAreVisibleImmediatelyAndHaveSeparateActions() {
        var toggles = 0
        var closes = 0
        var expands = 0
        render(onToggle = { toggles++ }, onClose = { closes++ }, onExpand = { expands++ })
        compose
            .onNodeWithContentDescription(context.getString(R.string.pause))
            .assertIsDisplayed()
            .performClick()
        compose
            .onNodeWithContentDescription(context.getString(R.string.player_close))
            .assertIsDisplayed()
            .performClick()
        compose
            .onRoot()
            .captureToImage()
            .asAndroidBitmap()
            .compress(
                android.graphics.Bitmap.CompressFormat.PNG,
                100,
                context.filesDir.resolve("video-mini-player.png").outputStream(),
            )
        compose.onNodeWithTag("video-mini-player").performTouchInput {
            click(Offset(center.x, height * .75f))
        }
        compose.runOnIdle {
            assertEquals(1, toggles)
            assertEquals(1, closes)
            assertEquals(1, expands)
        }
    }

    @Test
    fun viewerControlsFollowSyncplayPermissions() {
        render(canControl = false)
        compose.onNodeWithContentDescription(context.getString(R.string.pause)).assertIsNotEnabled()
        compose
            .onNodeWithContentDescription(context.getString(R.string.player_close))
            .assertIsEnabled()
        compose
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(
                    androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo
                )
            )
            .assertCountEquals(0)
    }

    @Test
    fun draggingSnapsToTheOppositeCornerAndDoesNotExpand() {
        var expands = 0
        render(onExpand = { expands++ })
        val player = compose.onNodeWithTag("video-mini-player")
        val before = player.fetchSemanticsNode().boundsInRoot
        player.performTouchInput {
            down(Offset(center.x, height * .75f))
            moveBy(Offset(-before.left, -before.top), 300)
            up()
        }
        compose.waitForIdle()
        val after = player.fetchSemanticsNode().boundsInRoot
        assertTrue(after.left < before.left)
        assertTrue(after.top < before.top)
        assertEquals(0, expands)
        val preferences = context.getSharedPreferences("video_miniplayer", Context.MODE_PRIVATE)
        assertFalse(preferences.getBoolean("right", true))
        assertFalse(preferences.getBoolean("bottom", true))
    }

    @Test
    fun twoFingerScalingStaysWithinTheScreen() {
        render()
        val player = compose.onNodeWithTag("video-mini-player")
        val before = player.fetchSemanticsNode().boundsInRoot
        player.performTouchInput {
            down(0, center - Offset(30f, 0f))
            down(1, center + Offset(30f, 0f))
            moveTo(0, center - Offset(180f, 0f))
            moveTo(1, center + Offset(180f, 0f))
            up(0)
            up(1)
        }
        compose.waitForIdle()
        val after = player.fetchSemanticsNode().boundsInRoot
        val screen = compose.onNodeWithTag("browse-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(after.width > before.width)
        assertTrue(after.left >= screen.left)
        assertTrue(after.right <= screen.right)
        assertTrue(after.top >= screen.top)
        assertTrue(after.bottom <= screen.bottom)
    }

    @Test
    fun releaseGlidesThroughIntermediatePositionsBeforeAligning() {
        render()
        val player = compose.onNodeWithTag("video-mini-player")
        val before = player.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        player.performTouchInput {
            down(Offset(center.x, height * .75f))
            moveBy(Offset(-before.left * .7f, -before.top * .7f), 300)
            up()
        }
        compose.mainClock.advanceTimeBy(32)
        val released = player.fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(80)
        val middle = player.fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(400)
        val aligned = player.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the first animation frames must stay away from the destination",
            released.top > aligned.top + 5f,
        )
        assertTrue(
            "the player must move through intermediate positions",
            middle.top < released.top && middle.top > aligned.top,
        )
        compose.mainClock.autoAdvance = true
    }
}

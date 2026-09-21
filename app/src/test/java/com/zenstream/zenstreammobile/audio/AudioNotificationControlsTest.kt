package com.zenstream.zenstreammobile.audio

import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioNotificationControlsTest {
    @Test
    fun expandedControlsKeepHeartTransportAndStopOrder() {
        assertEquals(
            listOf(
                AudioNotificationControl.Heart,
                AudioNotificationControl.Previous,
                AudioNotificationControl.PlayPause,
                AudioNotificationControl.Next,
                AudioNotificationControl.Stop,
            ),
            AudioNotificationControls.expandedOrder,
        )
    }

    @Test
    fun compactControlsUsePreviousPlayPauseAndNext() {
        assertEquals(
            listOf(
                AudioNotificationControl.Previous,
                AudioNotificationControl.PlayPause,
                AudioNotificationControl.Next,
            ),
            AudioNotificationControls.compactOrder,
        )
        assertEquals(
            0,
            AudioNotificationControls.compactIndexFor(AudioNotificationControl.Previous),
        )
        assertEquals(
            1,
            AudioNotificationControls.compactIndexFor(AudioNotificationControl.PlayPause),
        )
        assertEquals(2, AudioNotificationControls.compactIndexFor(AudioNotificationControl.Next))
        assertNull(AudioNotificationControls.compactIndexFor(AudioNotificationControl.Heart))
        assertNull(AudioNotificationControls.compactIndexFor(AudioNotificationControl.Stop))
    }

    @Test
    fun favoriteIconReflectsTheCurrentServerState() {
        assertEquals(
            CommandButton.ICON_HEART_FILLED,
            AudioNotificationControls.heartIcon(favorite = true),
        )
        assertEquals(
            CommandButton.ICON_HEART_UNFILLED,
            AudioNotificationControls.heartIcon(favorite = false),
        )
    }

    @Test
    fun customActionsHaveStableControlIdentifiers() {
        assertEquals(
            AudioNotificationControl.Heart,
            AudioNotificationControls.controlForAction(
                AudioNotificationControls.ACTION_TOGGLE_FAVORITE
            ),
        )
        assertEquals(
            AudioNotificationControl.Previous,
            AudioNotificationControls.controlForAction(AudioNotificationControls.ACTION_PREVIOUS),
        )
        assertEquals(
            AudioNotificationControl.Next,
            AudioNotificationControls.controlForAction(AudioNotificationControls.ACTION_NEXT),
        )
        assertEquals(
            AudioNotificationControl.Stop,
            AudioNotificationControls.controlForAction(AudioNotificationControls.ACTION_STOP),
        )
        assertNull(AudioNotificationControls.controlForAction("unknown"))
    }

    @Test
    fun onlyPlayPauseUsesTheSerializedPlayerCommand() {
        assertEquals(
            Player.COMMAND_PLAY_PAUSE,
            AudioNotificationControls.playerCommandFor(AudioNotificationControl.PlayPause),
        )
        assertNull(AudioNotificationControls.playerCommandFor(AudioNotificationControl.Heart))
        assertNull(AudioNotificationControls.playerCommandFor(AudioNotificationControl.Previous))
        assertNull(AudioNotificationControls.playerCommandFor(AudioNotificationControl.Next))
        assertNull(AudioNotificationControls.playerCommandFor(AudioNotificationControl.Stop))
    }
}

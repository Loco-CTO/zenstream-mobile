package com.zenstream.zenstreammobile.audio

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton

/** The controls exposed by the system media notification. */
internal enum class AudioNotificationControl {
    Heart,
    Previous,
    PlayPause,
    Next,
    Stop,
}

internal object AudioNotificationControls {
    const val ACTION_TOGGLE_FAVORITE =
        "com.zenstream.zenstreammobile.audio.notification.TOGGLE_FAVORITE"
    const val ACTION_PREVIOUS = "com.zenstream.zenstreammobile.audio.notification.PREVIOUS"
    const val ACTION_NEXT = "com.zenstream.zenstreammobile.audio.notification.NEXT"
    const val ACTION_STOP = "com.zenstream.zenstreammobile.audio.notification.STOP"

    val expandedOrder =
        listOf(
            AudioNotificationControl.Heart,
            AudioNotificationControl.Previous,
            AudioNotificationControl.PlayPause,
            AudioNotificationControl.Next,
            AudioNotificationControl.Stop,
        )

    val compactOrder =
        listOf(
            AudioNotificationControl.Previous,
            AudioNotificationControl.PlayPause,
            AudioNotificationControl.Next,
        )

    fun actionFor(control: AudioNotificationControl): String? =
        when (control) {
            AudioNotificationControl.Heart -> ACTION_TOGGLE_FAVORITE
            AudioNotificationControl.Previous -> ACTION_PREVIOUS
            AudioNotificationControl.PlayPause -> null
            AudioNotificationControl.Next -> ACTION_NEXT
            AudioNotificationControl.Stop -> ACTION_STOP
        }

    fun controlForAction(action: String): AudioNotificationControl? =
        when (action) {
            ACTION_TOGGLE_FAVORITE -> AudioNotificationControl.Heart
            ACTION_PREVIOUS -> AudioNotificationControl.Previous
            ACTION_NEXT -> AudioNotificationControl.Next
            ACTION_STOP -> AudioNotificationControl.Stop
            else -> null
        }

    fun heartIcon(favorite: Boolean): Int =
        if (favorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED

    @UnstableApi
    fun slotFor(control: AudioNotificationControl): Int =
        when (control) {
            AudioNotificationControl.Heart,
            AudioNotificationControl.Stop -> CommandButton.SLOT_OVERFLOW
            AudioNotificationControl.Previous -> CommandButton.SLOT_BACK
            AudioNotificationControl.PlayPause -> CommandButton.SLOT_CENTRAL
            AudioNotificationControl.Next -> CommandButton.SLOT_FORWARD
        }

    fun compactIndexFor(control: AudioNotificationControl): Int? =
        when (control) {
            AudioNotificationControl.Previous -> 0
            AudioNotificationControl.PlayPause -> 1
            AudioNotificationControl.Next -> 2
            AudioNotificationControl.Heart,
            AudioNotificationControl.Stop -> null
        }

    fun playerCommandFor(control: AudioNotificationControl): Int? =
        when (control) {
            AudioNotificationControl.PlayPause -> Player.COMMAND_PLAY_PAUSE
            AudioNotificationControl.Heart,
            AudioNotificationControl.Previous,
            AudioNotificationControl.Next,
            AudioNotificationControl.Stop -> null
        }
}

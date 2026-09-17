package com.zenstream.zenstreammobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal enum class PlaybackPipAction {
    Toggle,
    Previous,
    Next,
    Close,
}

internal data class PlaybackPipActionEvent(
    val instanceId: String,
    val action: PlaybackPipAction,
)

/** Process-local handoff from Android's PiP RemoteActions to the video view model. */
internal object PlaybackPipActionBus {
    private val _actions =
        MutableSharedFlow<PlaybackPipActionEvent>(
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val actions = _actions.asSharedFlow()

    fun dispatch(event: PlaybackPipActionEvent) {
        _actions.tryEmit(event)
    }
}

internal fun playbackPipActionForIntent(action: String?): PlaybackPipAction? =
    when (action) {
        PlaybackPipActionReceiver.ACTION_TOGGLE -> PlaybackPipAction.Toggle
        PlaybackPipActionReceiver.ACTION_PREVIOUS -> PlaybackPipAction.Previous
        PlaybackPipActionReceiver.ACTION_NEXT -> PlaybackPipAction.Next
        PlaybackPipActionReceiver.ACTION_CLOSE -> PlaybackPipAction.Close
        else -> null
    }

internal class PlaybackPipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID) ?: return
        val action = playbackPipActionForIntent(intent.action) ?: return
        PlaybackPipActionBus.dispatch(PlaybackPipActionEvent(instanceId, action))
    }

    companion object {
        const val EXTRA_INSTANCE_ID =
            "com.zenstream.zenstreammobile.extra.PIP_INSTANCE_ID"
        const val ACTION_TOGGLE = "com.zenstream.zenstreammobile.pip.TOGGLE"
        const val ACTION_PREVIOUS = "com.zenstream.zenstreammobile.pip.PREVIOUS"
        const val ACTION_NEXT = "com.zenstream.zenstreammobile.pip.NEXT"
        const val ACTION_CLOSE = "com.zenstream.zenstreammobile.pip.CLOSE"
    }
}

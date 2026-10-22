package com.zenstream.zenstreammobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.ui.PlaybackViewModel

internal enum class VideoPresentation {
    FULL,
    TO_MINI,
    MINI,
    TO_FULL,
}

internal class VideoPlaybackSession(val args: PlaybackLaunchArgs, val session: AuthSession) :
    ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    var playbackModel: PlaybackViewModel? = null
    var presentation by mutableStateOf(VideoPresentation.FULL)
    val handingOff: Boolean
        get() =
            presentation == VideoPresentation.TO_MINI || presentation == VideoPresentation.TO_FULL

    fun matches(session: AuthSession) =
        this.session.serverUrl == session.serverUrl && this.session.userId == session.userId
}

internal object VideoPlaybackSessions {
    var active by mutableStateOf<VideoPlaybackSession?>(null)
        private set

    fun open(args: PlaybackLaunchArgs, session: AuthSession): VideoPlaybackSession {
        val existing = active
        if (existing != null && existing.matches(session) && existing.args == args) {
            existing.presentation = VideoPresentation.FULL
            return existing
        }
        close()
        return VideoPlaybackSession(args, session).also { active = it }
    }

    fun close() {
        val previous = active
        active = null
        previous?.playbackModel?.requestClose()
        previous?.viewModelStore?.clear()
    }
}

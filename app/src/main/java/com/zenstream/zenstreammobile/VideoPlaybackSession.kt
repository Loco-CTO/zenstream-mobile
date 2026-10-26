package com.zenstream.zenstreammobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.offline.OfflineProfile
import com.zenstream.zenstreammobile.ui.PlaybackViewModel

internal enum class VideoPresentation {
    FULL,
    TO_MINI,
    MINI,
    TO_FULL,
}

internal class VideoPlaybackSession(
    val args: PlaybackLaunchArgs,
    val session: AuthSession?,
    val profile: OfflineProfile? = null,
) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    var playbackModel: PlaybackViewModel? = null
    var presentation by mutableStateOf(VideoPresentation.FULL)
    val handingOff: Boolean
        get() =
            presentation == VideoPresentation.TO_MINI || presentation == VideoPresentation.TO_FULL

    fun matches(session: AuthSession) =
        (profile?.serverUrl ?: this.session?.serverUrl) == session.serverUrl &&
            (profile?.account ?: this.session?.userId) == session.userId
}

internal object VideoPlaybackSessions {
    var active by mutableStateOf<VideoPlaybackSession?>(null)
        private set

    fun open(
        args: PlaybackLaunchArgs,
        session: AuthSession?,
        profile: OfflineProfile? = null,
    ): VideoPlaybackSession {
        require(session != null || profile != null)
        val existing = active
        if (
            existing != null &&
                (session?.let(existing::matches) ?: (existing.profile?.id == profile?.id)) &&
                existing.args == args
        ) {
            existing.presentation = VideoPresentation.FULL
            return existing
        }
        close()
        return VideoPlaybackSession(args, session, profile).also { active = it }
    }

    fun close() {
        val previous = active
        active = null
        previous?.playbackModel?.requestClose()
        previous?.playbackModel?.releaseResolvedSource()
        previous?.viewModelStore?.clear()
    }
}

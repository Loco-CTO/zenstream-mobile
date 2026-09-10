package com.zenstream.zenstreammobile.audio

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.common.util.UnstableApi
import com.zenstream.zenstreammobile.data.toJson
import com.zenstream.zenstreammobile.model.AudioPlayerState
import com.zenstream.zenstreammobile.model.AudioQueueEntry
import com.zenstream.zenstreammobile.model.AudioQueueSnapshot
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.AuthSession
import java.util.UUID
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.flow.StateFlow

/** UI-facing commands. All playback state and actual media loading live in AudioPlaybackService. */
@UnstableApi
class AudioPlayerCoordinator(
    context: Context,
    private val session: AuthSession,
) {
    private val appContext = context.applicationContext
    private var mediaController: MediaController? = null
    private val mediaControllerFuture =
        MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, AudioPlaybackService::class.java)),
        ).buildAsync()
    val state: StateFlow<AudioPlayerState> = AudioServiceBridge.state

    init {
        AudioServiceBridge.start(appContext)
        mediaControllerFuture.addListener(
            {
                mediaController = runCatching { mediaControllerFuture.get() }.getOrNull()
            },
            MoreExecutors.directExecutor(),
        )
    }

    fun playTracks(tracks: List<MediaItem>, selectedIndex: Int = 0, shuffle: Boolean = false) {
        val entries = tracks.filter { it.id.isNotBlank() }.map { track ->
            AudioQueueEntry(entryId = UUID.randomUUID().toString(), track = track)
        }
        if (entries.isEmpty()) return
        val selectedEntry = entries.getOrNull(selectedIndex.coerceIn(0, entries.lastIndex))
        val ordered = if (shuffle) entries.shuffled() else entries
        val index = ordered.indexOfFirst { it.entryId == selectedEntry?.entryId }.coerceAtLeast(0)
        val snapshot =
            AudioQueueSnapshot(
                serverUrl = session.serverUrl,
                userId = session.userId,
                entries = ordered,
                currentIndex = index,
                shuffle = shuffle,
            )
        AudioServiceBridge.start(appContext, AudioServiceBridge.ACTION_PLAY_QUEUE, snapshot)
    }

    fun addToQueue(tracks: List<MediaItem>) {
        val entries = tracks.filter { it.id.isNotBlank() }.map { track ->
            AudioQueueEntry(entryId = UUID.randomUUID().toString(), track = track)
        }
        if (entries.isEmpty()) return
        val snapshot =
            AudioQueueSnapshot(
                serverUrl = session.serverUrl,
                userId = session.userId,
                entries = entries,
                currentIndex = 0,
            )
        AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_ADD_QUEUE) {
            putExtra(AudioServiceBridge.EXTRA_SNAPSHOT, snapshot.toJson().toString())
        }
    }

    fun togglePlayback() {
        mediaController?.let { controller ->
            if (controller.currentMediaItem == null) {
                AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_TOGGLE_PLAYBACK)
            } else if (controller.isPlaying) {
                controller.pause()
            } else {
                controller.play()
            }
        } ?: AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_TOGGLE_PLAYBACK)
    }

    fun next() {
        val controller = mediaController
        if (controller != null && controller.currentMediaItem != null) {
            controller.seekToNextMediaItem()
        } else {
            AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_NEXT)
        }
    }

    fun previous() {
        val controller = mediaController
        if (controller != null && controller.currentMediaItem != null) {
            controller.seekToPreviousMediaItem()
        } else {
            AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_PREVIOUS)
        }
    }

    fun toggleShuffle() = AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_TOGGLE_SHUFFLE)

    fun toggleRepeat() = AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_TOGGLE_REPEAT)

    fun setVolume(value: Float) = AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_SET_VOLUME) {
        putExtra(AudioServiceBridge.EXTRA_VOLUME, value.coerceIn(0f, 1f))
    }

    fun toggleMute() = AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_TOGGLE_MUTE)

    fun seekTo(positionSeconds: Long) {
        val position = positionSeconds.coerceAtLeast(0L)
        mediaController?.takeIf { it.currentMediaItem != null }?.seekTo(position * 1_000L)
            ?: AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_SEEK) {
                putExtra(AudioServiceBridge.EXTRA_POSITION, position)
            }
    }

    fun removeQueueEntry(entryId: String) = AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_REMOVE_QUEUE) {
        putExtra(AudioServiceBridge.EXTRA_ENTRY_ID, entryId)
    }

    fun reorderQueue(from: Int, to: Int) = AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_REORDER_QUEUE) {
        putExtra(AudioServiceBridge.EXTRA_FROM, from)
        putExtra(AudioServiceBridge.EXTRA_TO, to)
    }

    fun clearQueue() = AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_CLEAR_QUEUE)

    fun pauseForVideo() = AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_PAUSE_FOR_VIDEO)

    fun updateFavorite(itemId: String, favorite: Boolean) =
        AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_UPDATE_FAVORITE) {
            putExtra(AudioServiceBridge.EXTRA_ITEM_ID, itemId)
            putExtra(AudioServiceBridge.EXTRA_FAVORITE, favorite)
        }

    fun stopAndClear() = AudioServiceBridge.command(appContext, AudioServiceBridge.ACTION_CLEAR_QUEUE)

    fun release() {
        mediaController?.release()
        mediaController = null
        mediaControllerFuture.cancel(false)
    }
}

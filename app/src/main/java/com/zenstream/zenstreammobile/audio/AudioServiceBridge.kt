package com.zenstream.zenstreammobile.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.zenstream.zenstreammobile.data.toJson
import com.zenstream.zenstreammobile.model.AudioPlayerState
import com.zenstream.zenstreammobile.model.AudioQueueSnapshot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

sealed class AudioCommandResult {
    data object Applied : AudioCommandResult()
    data class Failed(val message: String) : AudioCommandResult()
    data object TimedOut : AudioCommandResult()
}

/** Process-local command/state bridge. The MediaLibraryService remains the player authority. */
@UnstableApi
object AudioServiceBridge {
    const val ACTION_RESTORE = "com.zenstream.zenstreammobile.audio.RESTORE"
    const val ACTION_PLAY_QUEUE = "com.zenstream.zenstreammobile.audio.PLAY_QUEUE"
    const val ACTION_PLAY_QUEUE_ENTRY = "com.zenstream.zenstreammobile.audio.PLAY_QUEUE_ENTRY"
    const val ACTION_TOGGLE_PLAYBACK = "com.zenstream.zenstreammobile.audio.TOGGLE_PLAYBACK"
    const val ACTION_NEXT = "com.zenstream.zenstreammobile.audio.NEXT"
    const val ACTION_PREVIOUS = "com.zenstream.zenstreammobile.audio.PREVIOUS"
    const val ACTION_TOGGLE_SHUFFLE = "com.zenstream.zenstreammobile.audio.TOGGLE_SHUFFLE"
    const val ACTION_TOGGLE_REPEAT = "com.zenstream.zenstreammobile.audio.TOGGLE_REPEAT"
    const val ACTION_SET_VOLUME = "com.zenstream.zenstreammobile.audio.SET_VOLUME"
    const val ACTION_TOGGLE_MUTE = "com.zenstream.zenstreammobile.audio.TOGGLE_MUTE"
    const val ACTION_RETRY = "com.zenstream.zenstreammobile.audio.RETRY"
    const val ACTION_SEEK = "com.zenstream.zenstreammobile.audio.SEEK"
    const val ACTION_ADD_QUEUE = "com.zenstream.zenstreammobile.audio.ADD_QUEUE"
    const val ACTION_REMOVE_QUEUE = "com.zenstream.zenstreammobile.audio.REMOVE_QUEUE"
    const val ACTION_REORDER_QUEUE = "com.zenstream.zenstreammobile.audio.REORDER_QUEUE"
    const val ACTION_CLEAR_QUEUE = "com.zenstream.zenstreammobile.audio.CLEAR_QUEUE"
    const val ACTION_PAUSE_FOR_VIDEO = "com.zenstream.zenstreammobile.audio.PAUSE_FOR_VIDEO"
    const val ACTION_UPDATE_FAVORITE = "com.zenstream.zenstreammobile.audio.UPDATE_FAVORITE"
    const val EXTRA_SNAPSHOT = "snapshot"
    const val EXTRA_POSITION = "position"
    const val EXTRA_VOLUME = "volume"
    const val EXTRA_ENTRY_ID = "entryId"
    const val EXTRA_FROM = "from"
    const val EXTRA_TO = "to"
    const val EXTRA_ITEM_ID = "itemId"
    const val EXTRA_FAVORITE = "favorite"
    const val EXTRA_COMMAND_ID = "commandId"
    const val EXTRA_COMMAND_SEQUENCE = "commandSequence"

    private const val COMMAND_ACK_TIMEOUT_MILLIS = 2_000L

    private val _state = MutableStateFlow(AudioPlayerState())
    val state: StateFlow<AudioPlayerState> = _state.asStateFlow()
    private val commandSequence = AtomicLong(0L)
    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<AudioCommandResult>>()

    internal fun publish(value: AudioPlayerState) {
        _state.value = value
    }

    fun start(
        context: Context,
        action: String = ACTION_RESTORE,
        snapshot: AudioQueueSnapshot? = null,
    ) {
        val intent = newIntent(context, action)
        snapshot?.let { intent.putExtra(EXTRA_SNAPSHOT, it.toJson().toString()) }
        ContextCompat.startForegroundService(context.applicationContext, intent)
    }

    fun command(context: Context, action: String, configure: Intent.() -> Unit = {}) {
        val intent = newIntent(context, action)
        intent.configure()
        ContextCompat.startForegroundService(context.applicationContext, intent)
    }

    suspend fun commandAndAwait(
        context: Context,
        action: String,
        configure: Intent.() -> Unit = {},
    ): AudioCommandResult {
        val commandId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<AudioCommandResult>()
        pendingCommands[commandId] = deferred
        val intent =
            newIntent(context, action).apply {
                putExtra(EXTRA_COMMAND_ID, commandId)
                configure()
            }
        return try {
            ContextCompat.startForegroundService(context.applicationContext, intent)
            withTimeoutOrNull(COMMAND_ACK_TIMEOUT_MILLIS) { deferred.await() }
                ?: AudioCommandResult.TimedOut
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AudioCommandResult.Failed(error.message ?: "Audio command failed")
        } finally {
            pendingCommands.remove(commandId)
            deferred.cancel()
        }
    }

    internal fun complete(commandId: String?, result: AudioCommandResult) {
        if (commandId.isNullOrBlank()) return
        pendingCommands.remove(commandId)?.complete(result)
    }

    private fun newIntent(context: Context, action: String): Intent =
        Intent(context, AudioPlaybackService::class.java)
            .setAction(action)
            .putExtra(EXTRA_COMMAND_SEQUENCE, commandSequence.incrementAndGet())
}

package com.zenstream.zenstreammobile.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.zenstream.zenstreammobile.model.AudioPlayerState
import com.zenstream.zenstreammobile.model.MediaItem as CatalogMediaItem

internal data class AudioSessionQueueItem(
    val entryId: String,
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationUs: Long,
)

internal fun audioSessionQueueItems(state: AudioPlayerState): List<AudioSessionQueueItem> =
    state.queue.map { entry ->
        val durationSeconds =
            entry.track.durationSeconds?.takeIf { it.isFinite() && it >= 0.0 }
                ?: state.durationSeconds.toDouble().takeIf {
                    entry.entryId == state.currentEntry?.entryId && it > 0.0
                }
        val durationUs =
            durationSeconds
                ?.times(1_000_000.0)
                ?.takeIf { it.isFinite() && it <= Long.MAX_VALUE.toDouble() }
                ?.toLong() ?: C.TIME_UNSET
        AudioSessionQueueItem(
            entryId = entry.entryId,
            mediaId = "zenstream:queue:${entry.entryId}",
            title = entry.track.name,
            artist =
                entry.track.artistCredits.firstOrNull()?.name
                    ?: entry.track.albumArtist
                    ?: entry.track.artists.firstOrNull()
                    ?: "Unknown artist",
            album = entry.track.album,
            durationUs = durationUs,
        )
    }

internal interface AudioSessionPlayerController {
    fun setPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*>

    fun setMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*>

    fun seek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*>

    fun setRepeatMode(repeatMode: Int): ListenableFuture<*>

    fun setShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*>

    fun stop(): ListenableFuture<*>
}

/**
 * Keeps the service-owned audio queue visible to MediaSession while ExoPlayer contains only the
 * currently negotiated stream. Upcoming entries therefore carry catalog metadata, never playback
 * URLs or access tickets.
 */
@UnstableApi
internal class AudioSessionPlayer(
    player: Player,
    private val queueState: () -> AudioPlayerState,
    private val loading: () -> Boolean,
    private val desiredPlayWhenReady: () -> Boolean,
    private val artworkUri: (CatalogMediaItem) -> Uri?,
    private val controller: AudioSessionPlayerController,
) : ForwardingSimpleBasePlayer(player) {

    fun refreshQueueState() {
        invalidateState()
    }

    override fun getState(): State {
        val delegateState = super.getState()
        val state = queueState()
        val queueEntriesById = state.queue.associateBy { it.entryId }
        val projectedQueue =
            audioSessionQueueItems(state).map { item ->
                val queueEntry = checkNotNull(queueEntriesById[item.entryId])
                val metadata =
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setDisplayTitle(item.title)
                        .setArtist(item.artist)
                        .setAlbumTitle(item.album)
                        .setIsPlayable(true)
                        .apply { artworkUri(queueEntry.track)?.let(::setArtworkUri) }
                        .build()
                val mediaItem =
                    MediaItem.Builder().setMediaId(item.mediaId).setMediaMetadata(metadata).build()
                MediaItemData.Builder(item.entryId)
                    .setMediaItem(mediaItem)
                    .setMediaMetadata(metadata)
                    .setDurationUs(item.durationUs)
                    .setIsSeekable(item.durationUs != C.TIME_UNSET)
                    .build()
            }
        val hasQueue = projectedQueue.isNotEmpty()
        val availableCommands =
            Player.Commands.Builder()
                .addAll(delegateState.availableCommands)
                .addAll(
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_GET_METADATA,
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SET_MEDIA_ITEM,
                    Player.COMMAND_CHANGE_MEDIA_ITEMS,
                    Player.COMMAND_SET_REPEAT_MODE,
                    Player.COMMAND_SET_SHUFFLE_MODE,
                )
                .addIf(Player.COMMAND_SEEK_TO_DEFAULT_POSITION, hasQueue)
                .addIf(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, hasQueue)
                .addIf(Player.COMMAND_SEEK_TO_MEDIA_ITEM, hasQueue)
                .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasQueue)
                .addIf(Player.COMMAND_SEEK_TO_NEXT, hasQueue)
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasQueue)
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, hasQueue)
                .addIf(Player.COMMAND_SEEK_BACK, hasQueue)
                .addIf(Player.COMMAND_SEEK_FORWARD, hasQueue)
                .remove(Player.COMMAND_SET_MEDIA_ITEMS_METADATA)
                .remove(Player.COMMAND_SET_PLAYLIST_METADATA)
                .build()
        val currentIndex =
            state.currentIndex.takeIf { it in projectedQueue.indices } ?: C.INDEX_UNSET
        val isLoading = hasQueue && loading()
        val playbackState = if (isLoading) Player.STATE_BUFFERING else delegateState.playbackState
        return delegateState
            .buildUpon()
            .setAvailableCommands(availableCommands)
            .setPlaylist(projectedQueue)
            .setCurrentMediaItemIndex(currentIndex)
            .setContentPositionMs(state.positionMillis.coerceAtLeast(0L))
            .setPlaybackState(playbackState)
            .setIsLoading(isLoading || delegateState.isLoading)
            .setPlayWhenReady(
                if (hasQueue) desiredPlayWhenReady() else delegateState.playWhenReady,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setShuffleModeEnabled(state.shuffle)
            .setRepeatMode(state.repeatMode.toPlayerRepeatMode())
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        controller.setPlayWhenReady(playWhenReady)

    override fun handleSetMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<*> = controller.setMediaItems(mediaItems, startIndex, startPositionMs)

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> = controller.seek(mediaItemIndex, positionMs, seekCommand)

    override fun handleSetRepeatMode(repeatMode: Int): ListenableFuture<*> =
        controller.setRepeatMode(repeatMode)

    override fun handleSetShuffleModeEnabled(shuffleModeEnabled: Boolean): ListenableFuture<*> =
        controller.setShuffleModeEnabled(shuffleModeEnabled)

    override fun handleStop(): ListenableFuture<*> = controller.stop()

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> =
        unsupportedQueueMutation()

    override fun handleMoveMediaItems(
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int,
    ): ListenableFuture<*> = unsupportedQueueMutation()

    override fun handleReplaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: List<MediaItem>,
    ): ListenableFuture<*> = unsupportedQueueMutation()

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> =
        unsupportedQueueMutation()

    private fun unsupportedQueueMutation(): ListenableFuture<*> =
        Futures.immediateFailedFuture<Any>(
            UnsupportedOperationException("Queue changes are app-owned")
        )

    private fun com.zenstream.zenstreammobile.model.AudioRepeatMode.toPlayerRepeatMode(): Int =
        when (this) {
            com.zenstream.zenstreammobile.model.AudioRepeatMode.Off -> Player.REPEAT_MODE_OFF
            com.zenstream.zenstreammobile.model.AudioRepeatMode.Queue -> Player.REPEAT_MODE_ALL
            com.zenstream.zenstreammobile.model.AudioRepeatMode.Track -> Player.REPEAT_MODE_ONE
        }
}

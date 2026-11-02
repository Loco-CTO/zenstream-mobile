@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zenstream.zenstreammobile.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.audio.AudioPlayerCoordinator
import com.zenstream.zenstreammobile.audio.AudioServiceBridge
import com.zenstream.zenstreammobile.model.AudioLyrics
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.ui.components.musicArtworkPalette
import java.io.File
import kotlinx.coroutines.CancellationException

internal fun MediaItem.isOfflineMusic(): Boolean =
    type in setOf("Audio", "MusicAlbum", "MusicArtist")

internal fun downloadedAudioTracks(
    tracks: List<MediaItem>,
    downloadedIds: Set<String>,
): List<MediaItem> = tracks.filter { it.type == "Audio" && it.id in downloadedIds }

internal fun shouldReselectDownloadedAudio(
    currentTrackId: String?,
    requestedTrackId: String,
    currentProfileId: String?,
    requestedProfileId: String,
    playbackMode: String?,
): Boolean =
    currentTrackId == requestedTrackId &&
        currentProfileId == requestedProfileId &&
        playbackMode != "local"

@Composable
internal fun OfflineMusicMiniPlayer(profile: OfflineProfile, onOpen: (MediaItem) -> Unit) {
    val state by AudioServiceBridge.state.collectAsState()
    val item = state.currentEntry?.track ?: return
    if (state.offlineProfileId != profile.id) return
    ListItem(
        headlineContent = { Text(item.name, maxLines = 1) },
        supportingContent = { Text(stringResource(R.string.offline_music)) },
        modifier = Modifier.clickable { onOpen(item) },
    )
}

@Composable
internal fun OfflineSavedMusicScreen(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    root: MediaItem,
    onBack: () -> Unit,
    initialQueue: List<MediaItem>? = null,
    onItem: (MediaItem, List<MediaItem>?) -> Unit,
) {
    val context = LocalContext.current
    val controller = remember(profile.id) { AudioPlayerCoordinator(context, profile) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    LaunchedEffect(profile.id) {
        runtime.database.offline().profiles().collect { profiles ->
            if (profiles.none { it.id == profile.id && !it.locked }) onBack()
        }
    }
    val state by controller.state.collectAsState()
    val online by runtime.sessionStore.session.collectAsState(initial = null)
    val connection by runtime.connection.state.collectAsState()
    var offset by remember(root.id) { mutableIntStateOf(0) }
    var rows by remember(root.id) { mutableStateOf(emptyList<MediaItem>()) }
    var failed by remember(root.id) { mutableStateOf(false) }
    var downloaded by remember(root.id) { mutableStateOf(emptySet<String>()) }
    var innerPage by remember(root.id) { mutableStateOf("") }
    val revision by runtime.catalogRevision.collectAsState()
    val palette = remember(root.id, root.imageBlurHashes) { musicArtworkPalette(root) }
    val isTrack = root.type == "Audio"
    LaunchedEffect(profile.id, root.id, offset, revision) {
        try {
            rows =
                if (isTrack) listOf(root)
                else
                    runtime.database
                        .offline()
                        .musicChildren(profile.id, root.id, 100, offset)
                        .mapNotNull { runtime.catalog.item(profile.id, it.id) }
            downloaded =
                rows
                    .filter {
                        it.type == "Audio" &&
                            runtime.database.offline().playable(profile.id, it.id).isNotEmpty()
                    }
                    .map { it.id }
                    .toSet()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failed = true
        }
    }
    LaunchedEffect(profile.id, root.id) {
        if (isTrack) {
            if (initialQueue != null || state.currentEntry?.track?.id != root.id) {
                val queue = initialQueue ?: listOf(root)
                controller.playTracks(
                    queue,
                    queue.indexOfFirst { it.id == root.id }.coerceAtLeast(0),
                )
            } else if (
                shouldReselectDownloadedAudio(
                    currentTrackId = state.currentEntry?.track?.id,
                    requestedTrackId = root.id,
                    currentProfileId = state.offlineProfileId,
                    requestedProfileId = profile.id,
                    playbackMode = state.playbackMode,
                )
            ) {
                controller.playDownloadedCurrent()
            }
        }
    }
    Column(
        Modifier.fillMaxSize()
            .background(palette.background)
            .safeDrawingPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_arrow_left),
                    stringResource(R.string.back),
                )
            }
            Text(
                if (root.type == "Audio") state.currentEntry?.track?.name ?: root.name
                else root.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }
        if (failed)
            Text(
                stringResource(R.string.offline_request_failed),
                color = MaterialTheme.colorScheme.error,
            )
        if (isTrack && state.offlineProfileId == profile.id && state.currentEntry != null) {
            val playing = requireNotNull(state.currentEntry).track
            var art by remember(playing.id) { mutableStateOf<File?>(null) }
            var lyrics by remember(playing.id) { mutableStateOf<AudioLyrics?>(null) }
            LaunchedEffect(profile.id, playing.id, revision) {
                try {
                    art =
                        runtime.audio.artwork(profile.id, playing.id)
                            ?: playing.albumId?.let { runtime.audio.artwork(profile.id, it) }
                    lyrics = runtime.audio.lyrics(profile.id, playing.id)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    art = null
                    lyrics = null
                    failed = true
                }
            }
            when (innerPage) {
                "queue" ->
                    LazyColumn(Modifier.weight(1f)) {
                        items(state.queue, key = { it.entryId }) { entry ->
                            ListItem(
                                headlineContent = { Text(entry.track.name) },
                                modifier =
                                    Modifier.clickable { controller.playQueueEntry(entry.entryId) },
                            )
                        }
                    }
                "lyrics" ->
                    LazyColumn(Modifier.weight(1f)) {
                        val lines = lyrics?.lines.orEmpty()
                        if (lines.isEmpty())
                            item { Text(stringResource(R.string.offline_no_saved_lyrics)) }
                        items(lines.size) { index ->
                            val line = lines[index]
                            val active =
                                line.startSeconds?.let {
                                    it <= state.positionMillis / 1000.0 &&
                                        (line.endSeconds
                                            ?: lines.getOrNull(index + 1)?.startSeconds
                                            ?: Double.MAX_VALUE) > state.positionMillis / 1000.0
                                } == true
                            Text(
                                line.text,
                                color =
                                    if (active) palette.accent
                                    else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }
                else ->
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AsyncImage(
                            art,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth().weight(1f, false).aspectRatio(1f),
                        )
                        Text(playing.name, style = MaterialTheme.typography.titleLarge)
                        Text(playing.albumArtist.orEmpty())
                    }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (
                connection.mode == ConnectionMode.AUTOMATIC &&
                    online?.userId == profile.account &&
                    online?.serverUrl == profile.serverUrl
            ) {
                TextButton(onClick = controller::streamCurrent) {
                    Text(stringResource(R.string.offline_stream_track))
                }
            }
            Slider(
                value =
                    state.positionMillis
                        .coerceAtLeast(0)
                        .toFloat()
                        .coerceAtMost(state.durationSeconds.coerceAtLeast(1) * 1000f),
                onValueChange = { controller.seekTo((it / 1000).toLong()) },
                valueRange = 0f..state.durationSeconds.coerceAtLeast(1) * 1000f,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = controller::toggleShuffle) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_shuffle),
                        stringResource(R.string.shuffle),
                        tint =
                            if (state.shuffle) palette.accent
                            else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = controller::previous) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_skip_back),
                        stringResource(R.string.offline_audio_previous),
                    )
                }
                IconButton(onClick = controller::togglePlayback) {
                    Icon(
                        painterResource(
                            if (state.isPlaying) LucideR.drawable.lucide_ic_pause
                            else LucideR.drawable.lucide_ic_play
                        ),
                        stringResource(
                            if (state.isPlaying) R.string.offline_pause else R.string.play
                        ),
                    )
                }
                IconButton(onClick = controller::next) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_skip_forward),
                        stringResource(R.string.offline_audio_next),
                    )
                }
                IconButton(onClick = controller::toggleRepeat) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_repeat),
                        stringResource(R.string.offline_audio_repeat),
                        tint =
                            if (state.repeatMode.name != "Off") palette.accent
                            else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                        "queue" to R.string.offline_audio_queue,
                        "lyrics" to R.string.offline_audio_lyrics,
                    )
                    .forEach { (key, label) ->
                        FilterChip(
                            selected = innerPage == key,
                            onClick = { innerPage = if (innerPage == key) "" else key },
                            label = { Text(stringResource(label)) },
                        )
                    }
            }
        } else {
            val tracks = rows.filter { it.type == "Audio" }
            val playableTracks = downloadedAudioTracks(tracks, downloaded)
            if (playableTracks.isNotEmpty())
                TextButton(onClick = { controller.playTracks(playableTracks) }) {
                    Text(stringResource(R.string.offline_queue_this_page, playableTracks.size))
                }
            LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.id }) { item ->
                    ListItem(
                        headlineContent = { Text(item.name) },
                        supportingContent = {
                            if (item.type == "Audio")
                                Text(
                                    stringResource(
                                        if (item.id in downloaded) R.string.offline_saved
                                        else R.string.offline_audio_not_downloaded
                                    )
                                )
                        },
                        modifier =
                            Modifier.clickable(
                                enabled = item.type != "Audio" || item.id in downloaded
                            ) {
                                onItem(item, playableTracks.takeIf { item.type == "Audio" })
                            },
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            enabled = offset > 0,
                            onClick = { offset = (offset - 100).coerceAtLeast(0) },
                        ) {
                            Text(stringResource(R.string.offline_previous_page))
                        }
                        TextButton(enabled = rows.size == 100, onClick = { offset += 100 }) {
                            Text(stringResource(R.string.offline_next_page))
                        }
                    }
                }
            }
            OfflineMusicMiniPlayer(profile) { onItem(it, null) }
        }
    }
}

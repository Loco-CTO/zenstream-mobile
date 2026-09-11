@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:Suppress("UnsafeOptInUsageError")

package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.audio.AudioPlayerCoordinator
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.model.AudioLyrics
import com.zenstream.zenstreammobile.model.AudioPlayerState
import com.zenstream.zenstreammobile.model.AudioRepeatMode
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.PlaybackTimeDisplayMode
import com.zenstream.zenstreammobile.ui.components.ArtworkPalette
import com.zenstream.zenstreammobile.ui.components.decodeBlurHashBitmap
import com.zenstream.zenstreammobile.ui.components.MusicArtwork
import com.zenstream.zenstreammobile.ui.components.formatDurationSeconds
import com.zenstream.zenstreammobile.ui.components.musicArtworkPalette
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Composable
fun AudioMiniPlayer(
    state: AudioPlayerState,
    session: AuthSession,
    coordinator: AudioPlayerCoordinator,
    onOpenNowPlaying: () -> Unit,
    onFavorite: (MediaItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val entry = state.currentEntry ?: return
    val palette =
        remember(entry.track.id, entry.track.imageBlurHashes["Primary"]) {
            musicArtworkPalette(entry.track)
        }
    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .clickable(onClick = onOpenNowPlaying),
            shape = RoundedCornerShape(14.dp),
            color = palette.surface,
            tonalElevation = 0.dp,
        ) {
            Column {
                Row(
                    modifier =
                        Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MusicArtwork(
                        entry.track,
                        session,
                        modifier = Modifier.size(46.dp),
                        contentDescription = entry.track.name,
                        requestedSize = 256,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            entry.track.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            entry.track.albumArtist
                                ?: entry.track.artists.firstOrNull()
                                ?: "Unknown artist",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { onFavorite(entry.track) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_heart),
                            contentDescription =
                                if (entry.track.favorite) "Remove favorite" else "Add favorite",
                            tint =
                                if (entry.track.favorite) palette.accent
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = coordinator::togglePlayback,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painterResource(
                                if (state.isPlaying) LucideR.drawable.lucide_ic_pause
                                else LucideR.drawable.lucide_ic_play
                            ),
                            contentDescription = if (state.isPlaying) "Pause" else "Play",
                        )
                    }
                    IconButton(onClick = coordinator::next, modifier = Modifier.size(40.dp)) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_skip_forward),
                            contentDescription = "Next track",
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = {
                        if (state.durationSeconds > 0) {
                            (state.positionSeconds.toFloat() / state.durationSeconds).coerceIn(
                                0f,
                                1f,
                            )
                        } else 0f
                    },
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(3.dp)
                            .clip(
                                RoundedCornerShape(
                                    bottomStart = 14.dp,
                                    bottomEnd = 14.dp,
                                )
                            ),
                    color = palette.accent,
                    trackColor = Color.White.copy(alpha = .16f),
                )
            }
        }
    }
}

@Composable
fun NowPlayingScreen(
    repository: CatalogRepository,
    session: AuthSession,
    coordinator: AudioPlayerCoordinator,
    onBack: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onFavorite: (MediaItem) -> Unit = {},
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    val current = state.currentEntry?.track
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context) { SessionStore(context.applicationContext) }
    val timerMode by
        store.playbackTimeDisplayMode.collectAsStateWithLifecycle(
            initialValue = PlaybackTimeDisplayMode.Remaining
        )
    val scope = rememberCoroutineScope()
    var lyrics by remember(current?.id) { mutableStateOf<AudioLyrics?>(null) }
    var lyricsLoading by remember(current?.id) { mutableStateOf(false) }
    var lyricsError by remember(current?.id) { mutableStateOf(false) }
    var lyricsRetry by remember(current?.id) { mutableIntStateOf(0) }

    LaunchedEffect(tab, current?.id, lyricsRetry) {
        if (tab == 1 && current != null && lyrics == null && !lyricsLoading) {
            lyricsLoading = true
            lyricsError = false
            try {
                lyrics = repository.audioLyrics(session, current.id)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (
                    error is com.zenstream.zenstreammobile.data.CatalogException &&
                        error.statusCode == 401
                ) {
                    repository.clearSessionIfCurrent(session)
                }
                lyricsError = true
            }
            lyricsLoading = false
        }
    }

    val artworkPalette =
        remember(current?.id, current?.imageBlurHashes?.get("Primary")) {
            current?.let(::musicArtworkPalette) ?: ArtworkPalette.fallback
        }
    val backgroundBlurHash =
        remember(current?.id, current?.imageBlurHashes?.get("Primary")) {
            current?.imageBlurHashes?.get("Primary")
                ?.takeIf(String::isNotBlank)
                ?.let(::decodeBlurHashBitmap)
        }
    Box(
        Modifier.fillMaxSize().background(artworkPalette.background)
    ) {
        backgroundBlurHash?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f)))
        }
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "PLAYING FROM ALBUM",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                current?.album ?: "Now playing",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_chevron_down),
                                contentDescription = "Close now playing",
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { tab = 0 },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_ellipsis_vertical),
                                contentDescription = "Open queue and player actions",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            containerColor = Color.Transparent,
        ) { padding ->
            if (current == null) {
                Box(
                    Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Your audio queue is empty",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "now-playing-main") {
                        NowPlayingMain(
                            state = state,
                            current = current,
                            session = session,
                            timerMode = timerMode,
                            palette = artworkPalette,
                            onToggleTimer = {
                                scope.launch {
                                    store.savePlaybackTimeDisplayMode(timerMode.toggled())
                                }
                            },
                            onOpenAlbum = onOpenAlbum,
                            onOpenArtist = onOpenArtist,
                            onFavorite = onFavorite,
                            coordinator = coordinator,
                            onOpenQueue = { tab = 0 },
                            onOpenLyrics = { tab = 1 },
                        )
                    }
                    if (!state.error.isNullOrBlank()) {
                        item(key = "now-playing-error") {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .85f),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        state.error ?: "Audio could not be played",
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = coordinator::retry) { Text("Retry") }
                                }
                            }
                        }
                    }
                    item(key = "now-playing-tabs") {
                        TabRow(
                            selectedTabIndex = tab,
                            containerColor = Color.Transparent,
                            contentColor = artworkPalette.accent,
                        ) {
                            Tab(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                selectedContentColor = artworkPalette.accent,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = { Text("Queue · ${state.queue.size}") },
                            )
                            Tab(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                selectedContentColor = artworkPalette.accent,
                                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                text = { Text("Lyrics") },
                            )
                        }
                    }
                    item(key = if (tab == 0) "queue-panel" else "lyrics-panel") {
                        Box(Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 380.dp)) {
                            if (tab == 0) {
                                QueuePanel(state, coordinator, onOpenAlbum, onOpenArtist)
                            } else {
                                LyricsPanel(
                                    lyrics = lyrics,
                                    loading = lyricsLoading,
                                    error = lyricsError,
                                    positionSeconds = state.positionSeconds,
                                    onSeek = coordinator::seekTo,
                                    onRetry = {
                                        lyrics = null
                                        lyricsError = false
                                        lyricsRetry += 1
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingMain(
    state: AudioPlayerState,
    current: MediaItem,
    session: AuthSession,
    timerMode: PlaybackTimeDisplayMode,
    palette: ArtworkPalette,
    onToggleTimer: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onFavorite: (MediaItem) -> Unit,
    coordinator: AudioPlayerCoordinator,
    onOpenQueue: () -> Unit,
    onOpenLyrics: () -> Unit,
) {
    val accent = palette.accent
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val artSize = maxWidth.coerceAtMost(320.dp).coerceAtLeast(220.dp)
            Surface(
                modifier = Modifier.size(artSize),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                MusicArtwork(
                    current,
                    session,
                    modifier = Modifier.fillMaxSize(),
                    contentDescription = current.name,
                    requestedSize = 640,
                    shape = RoundedCornerShape(18.dp),
                )
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(
            current.name,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().semantics { heading() },
        )
        Text(
            current.albumArtist ?: current.artists.firstOrNull() ?: "Unknown artist",
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable { current.artistId?.let(onOpenArtist) },
        )
        current.album?.takeIf(String::isNotBlank)?.let { album ->
            Text(
                album,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { current.albumId?.let(onOpenAlbum) },
            )
        }
        Spacer(Modifier.height(18.dp))
        AudioProgressScrubber(
            positionSeconds = state.positionSeconds,
            durationSeconds = state.durationSeconds,
            accent = accent,
            onSeek = { coordinator.seekTo(it) },
        )
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggleTimer),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (timerMode == PlaybackTimeDisplayMode.Remaining) {
                    "-${formatDurationSeconds((state.durationSeconds - state.positionSeconds).coerceAtLeast(0L).toDouble())}"
                } else {
                    formatDurationSeconds(state.positionSeconds.toDouble())
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                formatDurationSeconds(state.durationSeconds.toDouble()),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier =
                    Modifier.semantics {
                        contentDescription =
                            if (timerMode == PlaybackTimeDisplayMode.Remaining) {
                                "Show elapsed time"
                            } else {
                                "Show remaining time"
                            }
                    },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 22.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = coordinator::toggleShuffle, modifier = Modifier.size(52.dp)) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_shuffle),
                    contentDescription =
                        if (state.shuffle) "Turn off shuffle" else "Turn on shuffle",
                    tint =
                        if (state.shuffle) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = coordinator::previous, modifier = Modifier.size(52.dp)) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_skip_back),
                    contentDescription = "Previous track",
                )
            }
            IconButton(onClick = coordinator::togglePlayback, modifier = Modifier.size(76.dp)) {
                Surface(shape = CircleShape, color = accent, contentColor = palette.onAccent) {
                    Icon(
                        painterResource(
                            if (state.isPlaying) LucideR.drawable.lucide_ic_pause
                            else LucideR.drawable.lucide_ic_play
                        ),
                        contentDescription = if (state.isPlaying) "Pause" else "Play",
                        modifier = Modifier.padding(22.dp),
                        tint = palette.onAccent,
                    )
                }
            }
            IconButton(onClick = coordinator::next, modifier = Modifier.size(52.dp)) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_skip_forward),
                    contentDescription = "Next track",
                )
            }
            IconButton(onClick = coordinator::toggleRepeat, modifier = Modifier.size(52.dp)) {
                Icon(
                    painterResource(
                        if (state.repeatMode == AudioRepeatMode.Track)
                            LucideR.drawable.lucide_ic_repeat_1
                        else LucideR.drawable.lucide_ic_repeat
                    ),
                    contentDescription =
                        when (state.repeatMode) {
                            AudioRepeatMode.Off -> "Turn on repeat"
                            AudioRepeatMode.Queue -> "Repeat queue"
                            AudioRepeatMode.Track -> "Repeat track"
                        },
                    tint =
                        if (state.repeatMode != AudioRepeatMode.Off) accent
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 22.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenQueue, modifier = Modifier.size(52.dp)) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_list_filter),
                    contentDescription = "Open queue",
                    tint =
                        if (state.queue.isNotEmpty()) accent
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenLyrics, modifier = Modifier.size(52.dp)) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_audio_lines),
                    contentDescription = "Open lyrics",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onFavorite(current) }, modifier = Modifier.size(52.dp)) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_heart),
                    contentDescription =
                        if (current.favorite) "Remove favorite" else "Add favorite",
                    tint =
                        if (current.favorite) accent
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Artwork-led seek control with a slim rail and an intentional, easy-to-grab handle. */
@Composable
private fun AudioProgressScrubber(
    positionSeconds: Long,
    durationSeconds: Long,
    accent: Color,
    onSeek: (Long) -> Unit,
) {
    val duration = durationSeconds.coerceAtLeast(1L)
    val position = positionSeconds.coerceIn(0L, duration)
    val fraction = position.toFloat() / duration.toFloat()
    Box(
        modifier = Modifier.fillMaxWidth().height(38.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val horizontalInset = 14.dp.toPx()
            val centerY = size.height / 2f
            val trackWidth = (size.width - horizontalInset * 2f).coerceAtLeast(0f)
            val startX = horizontalInset
            val endX = startX + trackWidth
            val thumbX = startX + trackWidth * fraction
            val trackStroke = 6.dp.toPx()

            drawLine(
                color = Color.White.copy(alpha = .13f),
                start = Offset(startX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = trackStroke,
                cap = StrokeCap.Round,
            )
            if (fraction > 0f) {
                drawLine(
                    color = accent,
                    start = Offset(startX, centerY),
                    end = Offset(thumbX, centerY),
                    strokeWidth = trackStroke,
                    cap = StrokeCap.Round,
                )
            }
            if (durationSeconds > 0) {
                drawCircle(
                    color = accent,
                    radius = 9.dp.toPx(),
                    center = Offset(thumbX, centerY),
                )
            }
        }
        // Keep the standard semantics and drag/tap behavior while the canvas owns the visual.
        Slider(
            value = position.toFloat(),
            onValueChange = { onSeek(it.toLong().coerceIn(0L, duration)) },
            valueRange = 0f..duration.toFloat(),
            enabled = durationSeconds > 0,
            modifier = Modifier.fillMaxWidth().height(38.dp),
            colors =
                SliderDefaults.colors(
                    thumbColor = Color.Transparent,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
        )
    }
}

@Composable
private fun QueuePanel(
    state: AudioPlayerState,
    coordinator: AudioPlayerCoordinator,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    val list = state.queue
    LazyColumn(
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(list, key = { _, entry -> entry.entryId }) { index, entry ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color =
                    if (index == state.currentIndex) {
                        MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                    } else Color.Transparent,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        modifier = Modifier.width(28.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        Modifier.weight(1f).clickable {
                            entry.track.albumId?.let(onOpenAlbum)
                        }
                    ) {
                        Text(entry.track.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            entry.track.albumArtist ?: "Unknown artist",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier =
                                Modifier.clickable { entry.track.artistId?.let(onOpenArtist) },
                        )
                    }
                    IconButton(
                        onClick = { coordinator.reorderQueue(index, index - 1) },
                        enabled = index > 0,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_arrow_up),
                            contentDescription = "Move up",
                        )
                    }
                    IconButton(
                        onClick = { coordinator.reorderQueue(index, index + 1) },
                        enabled = index < list.lastIndex,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_arrow_down),
                            contentDescription = "Move down",
                        )
                    }
                    IconButton(
                        onClick = { coordinator.removeQueueEntry(entry.entryId) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_x),
                            contentDescription = "Remove from queue",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (list.isNotEmpty()) {
            item(key = "clear-queue") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = coordinator::clearQueue) { Text("Clear queue") }
                    TextButton(onClick = coordinator::clearQueue) { Text("Start fresh") }
                }
            }
        }
    }
}

@Composable
private fun LyricsPanel(
    lyrics: AudioLyrics?,
    loading: Boolean,
    error: Boolean,
    positionSeconds: Long,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        loading ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        error ->
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Could not load lyrics", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        lyrics == null ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Lyrics unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        else -> {
            val listState = rememberLazyListState()
            var followCurrentLine by remember { mutableStateOf(true) }
            var automaticScroll by remember { mutableStateOf(false) }
            val currentIndex =
                if (lyrics.timed) {
                    lyrics.lines.indexOfLast {
                        it.startSeconds != null && it.startSeconds <= positionSeconds
                    }
                } else -1
            LaunchedEffect(listState) {
                snapshotFlow { listState.isScrollInProgress }
                    .collect { scrolling ->
                        if (scrolling && !automaticScroll) followCurrentLine = false
                    }
            }
            LaunchedEffect(currentIndex, followCurrentLine) {
                if (followCurrentLine && currentIndex >= 0) {
                    automaticScroll = true
                    listState.animateScrollToItem(currentIndex)
                    automaticScroll = false
                }
            }
            Column(Modifier.fillMaxSize()) {
                if (!followCurrentLine && lyrics.timed) {
                    TextButton(
                        onClick = { followCurrentLine = true },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Text("Follow current line")
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(lyrics.lines, key = { index, _ -> "lyric-$index" }) { index, line
                        ->
                        Text(
                            line.text,
                            style =
                                if (index == currentIndex) MaterialTheme.typography.titleLarge
                                else MaterialTheme.typography.bodyLarge,
                            color =
                                if (index == currentIndex) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier =
                                Modifier.fillMaxWidth().clickable {
                                    line.startSeconds?.toLong()?.let(onSeek)
                                },
                        )
                    }
                }
            }
        }
    }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@file:Suppress("UnsafeOptInUsageError")

package com.zenstream.zenstreammobile.ui.screens

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.audio.AudioPlayerCoordinator
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.model.AudioLyrics
import com.zenstream.zenstreammobile.model.AudioPlayerState
import com.zenstream.zenstreammobile.model.AudioQueueEntry
import com.zenstream.zenstreammobile.model.AudioRepeatMode
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.LyricLine
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.PlaybackTimeDisplayMode
import com.zenstream.zenstreammobile.ui.components.ArtworkPalette
import com.zenstream.zenstreammobile.ui.components.MusicArtwork
import com.zenstream.zenstreammobile.ui.components.decodeBlurHashBitmap
import com.zenstream.zenstreammobile.ui.components.formatDurationSeconds
import com.zenstream.zenstreammobile.ui.components.musicArtworkPalette
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private fun repeatIconResource(mode: AudioRepeatMode): Int =
    when (mode) {
        AudioRepeatMode.Off -> R.drawable.lucide_ic_repeat_off
        AudioRepeatMode.Queue -> LucideR.drawable.lucide_ic_repeat
        AudioRepeatMode.Track -> LucideR.drawable.lucide_ic_repeat_1
    }

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
    val density = LocalDensity.current
    val gestureThresholdPx = with(density) { 72.dp.toPx() }
    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 5.dp)
                    .pointerInput(entry.entryId) {
                        var totalDrag = Offset.Zero
                        detectDragGestures(
                            onDragStart = {
                                totalDrag = Offset.Zero
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                totalDrag += dragAmount
                            },
                            onDragCancel = {
                                totalDrag = Offset.Zero
                            },
                            onDragEnd = {
                                val horizontalDominant = abs(totalDrag.x) > abs(totalDrag.y)
                                when {
                                    horizontalDominant && abs(totalDrag.x) >= gestureThresholdPx ->
                                        onOpenNowPlaying()
                                    !horizontalDominant && totalDrag.y <= -gestureThresholdPx ->
                                        onOpenNowPlaying()
                                    !horizontalDominant && totalDrag.y >= gestureThresholdPx ->
                                        coordinator.stopAndClear()
                                }
                                totalDrag = Offset.Zero
                            },
                        )
                    }
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
                            style =
                                MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold
                                ),
                        )
                        Text(
                            entry.track.albumArtist
                                ?: entry.track.artists.firstOrNull()
                                ?: stringResource(R.string.audio_unknown_artist),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
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
                                stringResource(
                                    if (entry.track.favorite) R.string.remove_favorite
                                    else R.string.add_favorite
                                ),
                            tint =
                                if (entry.track.favorite) palette.accent
                                else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = coordinator::stopAndClear,
                        modifier = Modifier.size(40.dp).testTag("audio-mini-stop"),
                    ) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_square),
                            contentDescription = stringResource(R.string.stop_playing),
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
                            contentDescription =
                                stringResource(
                                    if (state.isPlaying) R.string.pause else R.string.play
                                ),
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
    onOpenArtist: (String) -> Unit,
    onFavorite: (MediaItem) -> Unit = {},
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    // The player itself is a fixed, one-page control surface. Queue and lyrics are separate
    // inner pages so the primary player never becomes a competing scrolling surface.
    val current = state.currentEntry?.track
    var innerPage by remember(current?.id) { mutableIntStateOf(0) }
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

    LaunchedEffect(current?.id, lyricsRetry) {
        if (current != null && lyrics == null && !lyricsLoading) {
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
            current
                ?.imageBlurHashes
                ?.get("Primary")
                ?.takeIf(String::isNotBlank)
                ?.let(::decodeBlurHashBitmap)
        }
    Box(Modifier.fillMaxSize().background(artworkPalette.background)) {
        backgroundBlurHash?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f)))
        }
        val queuePage = innerPage == 1
        val pageTitle =
            if (queuePage) stringResource(R.string.audio_queue)
            else
                current?.album?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.audio_now_playing)
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Box(
                            Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!queuePage) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        stringResource(R.string.audio_playing_from_album),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        pageTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            } else {
                                Text(
                                    pageTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (queuePage) innerPage = 0 else onBack()
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painterResource(
                                    if (queuePage) LucideR.drawable.lucide_ic_arrow_left
                                    else LucideR.drawable.lucide_ic_chevron_down
                                ),
                                contentDescription =
                                    if (queuePage) {
                                        stringResource(R.string.audio_back_to_now_playing)
                                    } else {
                                        stringResource(R.string.audio_close_now_playing)
                                    },
                                tint = artworkPalette.accent,
                            )
                        }
                    },
                    actions = { Spacer(Modifier.size(48.dp)) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            containerColor = Color.Transparent,
        ) { padding ->
            val bodyModifier = Modifier.fillMaxSize().padding(padding).navigationBarsPadding()
            Column(bodyModifier) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        current == null ->
                            Box(
                                Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    stringResource(R.string.audio_empty_queue),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        innerPage == 0 ->
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
                                onOpenArtist = onOpenArtist,
                                onFavorite = onFavorite,
                                coordinator = coordinator,
                                modifier = Modifier.fillMaxSize(),
                            )
                        innerPage == 1 ->
                            QueuePanel(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                snapshot =
                                    remember(state.queue, state.currentEntry?.entryId) {
                                        QueuePanelSnapshot(
                                            entries = state.queue,
                                            currentEntryId = state.currentEntry?.entryId,
                                        )
                                    },
                                session = session,
                                coordinator = coordinator,
                                accent = artworkPalette.accent,
                            )
                        else ->
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
                                onOpenArtist = onOpenArtist,
                                onFavorite = onFavorite,
                                coordinator = coordinator,
                                showLyrics = innerPage == 2,
                                lyrics = lyrics,
                                lyricsLoading = lyricsLoading,
                                lyricsError = lyricsError,
                                onRetryLyrics = {
                                    lyrics = null
                                    lyricsError = false
                                    lyricsRetry += 1
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                    }
                }
                if (current != null && innerPage != 1 && !state.error.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .9f),
                        shape = RoundedCornerShape(14.dp),
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                state.error ?: stringResource(R.string.audio_playback_failed),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = coordinator::retry,
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = artworkPalette.accent
                                    ),
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
                NowPlayingInnerTabs(
                    selectedPage = innerPage,
                    accent = artworkPalette.accent,
                    onQueueClick = { innerPage = if (innerPage == 1) 0 else 1 },
                    onLyricsClick = { innerPage = if (innerPage == 2) 0 else 2 },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
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
    onOpenArtist: (String) -> Unit,
    onFavorite: (MediaItem) -> Unit,
    coordinator: AudioPlayerCoordinator,
    showLyrics: Boolean = false,
    lyrics: AudioLyrics? = null,
    lyricsLoading: Boolean = false,
    lyricsError: Boolean = false,
    onRetryLyrics: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val accent = palette.accent
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            NowPlayingLandscape(
                state = state,
                current = current,
                session = session,
                timerMode = timerMode,
                palette = palette,
                onToggleTimer = onToggleTimer,
                onOpenArtist = onOpenArtist,
                onFavorite = onFavorite,
                coordinator = coordinator,
                showLyrics = showLyrics,
                lyrics = lyrics,
                lyricsLoading = lyricsLoading,
                lyricsError = lyricsError,
                onRetryLyrics = onRetryLyrics,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val compact = maxHeight < 600.dp
            val controlSize = if (compact) 46.dp else 52.dp
            val playButtonSize = if (compact) 64.dp else 76.dp
            val displayDurationSeconds = resolvedAudioDurationSeconds(state, current)
            val displayPositionSeconds =
                state.positionSeconds.coerceAtLeast(0L).let { position ->
                    if (displayDurationSeconds > 0L) position.coerceAtMost(displayDurationSeconds)
                    else position
                }
            val timerDescription =
                stringResource(
                    if (timerMode == PlaybackTimeDisplayMode.Remaining) {
                        R.string.player_show_elapsed_time
                    } else {
                        R.string.player_show_remaining_time
                    }
                )
            Column(
                Modifier.fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = if (compact) 2.dp else 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    val maxArtworkWidth = maxWidth
                    // Keep the metadata-to-progress relationship fixed. Only the artwork is allowed
                    // to absorb extra vertical space, while this whole block is centered above the
                    // bottom controls.
                    val fixedBlockHeight = if (compact) 124.dp else 158.dp
                    val availableArtworkHeight = (maxHeight - fixedBlockHeight).coerceAtLeast(1.dp)
                    val artworkMax = if (compact) 260.dp else 360.dp
                    val artworkSize =
                        minOf(availableArtworkHeight, maxArtworkWidth, artworkMax)
                            .coerceAtLeast(1.dp)
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (showLyrics) {
                            LyricsPanel(
                                modifier = Modifier.size(artworkSize).padding(8.dp),
                                trackKey = current.id,
                                lyrics = lyrics,
                                loading = lyricsLoading,
                                error = lyricsError,
                                positionSeconds = state.positionSeconds,
                                positionMillis = state.positionMillis,
                                positionUpdatedAtElapsedRealtime =
                                    state.positionUpdatedAtElapsedRealtime,
                                isPlaying = state.isPlaying,
                                accent = accent,
                                onSeek = coordinator::seekTo,
                                onRetry = onRetryLyrics,
                            )
                        } else {
                            Surface(
                                modifier = Modifier.size(artworkSize),
                                shape = RoundedCornerShape(18.dp),
                                color = palette.surface,
                            ) {
                                MusicArtwork(
                                    current,
                                    session,
                                    modifier = Modifier.fillMaxSize(),
                                    contentDescription = current.name,
                                    requestedSize = 512,
                                    shape = RoundedCornerShape(18.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                    Text(
                                        current.name,
                                        style =
                                            MaterialTheme.typography.titleLarge.copy(
                                                fontWeight = FontWeight.Bold
                                            ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.fillMaxWidth().semantics { heading() },
                                    )
                                    Text(
                                        current.albumArtist
                                            ?: current.artists.firstOrNull()
                                            ?: stringResource(R.string.audio_unknown_artist),
                                        color = accent,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier =
                                            Modifier.fillMaxWidth().clickable {
                                                current.artistId?.let(onOpenArtist)
                                            },
                                    )
                                }
                                IconButton(
                                    onClick = { onFavorite(current) },
                                    modifier = Modifier.size(controlSize),
                                ) {
                                    Icon(
                                        painterResource(LucideR.drawable.lucide_ic_heart),
                                        contentDescription =
                                            stringResource(
                                                if (current.favorite) R.string.remove_favorite
                                                else R.string.add_favorite
                                            ),
                                        tint =
                                            if (current.favorite) accent
                                            else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                            AudioQualityLine(state = state, accent = accent)
                        }
                        // This is intentionally a fixed gap: the info block always stays directly
                        // above the progress bar regardless of the device height.
                        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                        AudioProgressScrubber(
                            trackKey = state.currentEntry?.entryId ?: current.id,
                            positionSeconds = displayPositionSeconds,
                            durationSeconds = displayDurationSeconds,
                            accent = accent,
                            onSeek = { coordinator.seekTo(it) },
                        )
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = onToggleTimer),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                if (timerMode == PlaybackTimeDisplayMode.Remaining) {
                                    "-${formatDurationSeconds((displayDurationSeconds - displayPositionSeconds).coerceAtLeast(0L).toDouble())}"
                                } else {
                                    formatDurationSeconds(displayPositionSeconds.toDouble())
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                formatDurationSeconds(displayDurationSeconds.toDouble()),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                                modifier =
                                    Modifier.semantics {
                                        contentDescription = timerDescription
                                    },
                            )
                        }
                    }
                }
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = coordinator::toggleShuffle,
                            modifier = Modifier.size(controlSize).testTag("audio-control-shuffle"),
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_shuffle),
                                contentDescription =
                                    stringResource(
                                        if (state.shuffle) R.string.audio_turn_off_shuffle
                                        else R.string.audio_turn_on_shuffle
                                    ),
                                tint =
                                    if (state.shuffle) accent
                                    else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        IconButton(
                            onClick = coordinator::previous,
                            modifier = Modifier.size(controlSize).testTag("audio-control-previous"),
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_skip_back),
                                contentDescription = stringResource(R.string.audio_previous_track),
                                tint = accent,
                            )
                        }
                        IconButton(
                            onClick = coordinator::togglePlayback,
                            modifier = Modifier.size(playButtonSize).testTag("audio-control-play"),
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = CircleShape,
                                color = accent,
                                contentColor = palette.onAccent,
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(
                                        painterResource(
                                            if (state.isPlaying) LucideR.drawable.lucide_ic_pause
                                            else LucideR.drawable.lucide_ic_play
                                        ),
                                        contentDescription =
                                            stringResource(
                                                if (state.isPlaying) R.string.pause
                                                else R.string.play
                                            ),
                                        modifier = Modifier.size(if (compact) 26.dp else 32.dp),
                                        tint = palette.onAccent,
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = coordinator::next,
                            modifier = Modifier.size(controlSize).testTag("audio-control-next"),
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_skip_forward),
                                contentDescription = stringResource(R.string.audio_next_track),
                                tint = accent,
                            )
                        }
                        IconButton(
                            onClick = coordinator::toggleRepeat,
                            modifier = Modifier.size(controlSize).testTag("audio-control-repeat"),
                        ) {
                            Icon(
                                painterResource(repeatIconResource(state.repeatMode)),
                                contentDescription =
                                    when (state.repeatMode) {
                                        AudioRepeatMode.Off ->
                                            stringResource(R.string.audio_turn_on_repeat)
                                        AudioRepeatMode.Queue ->
                                            stringResource(R.string.audio_repeat_queue)
                                        AudioRepeatMode.Track ->
                                            stringResource(R.string.audio_repeat_track)
                                    },
                                tint =
                                    if (state.repeatMode != AudioRepeatMode.Off) accent
                                    else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingInnerTabs(
    selectedPage: Int,
    accent: Color,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val queueDescription = stringResource(R.string.audio_open_queue)
    val lyricsDescription = stringResource(R.string.audio_open_lyrics)
    Row(
        modifier.height(64.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onQueueClick,
            modifier =
                Modifier.size(52.dp)
                    .semantics {
                        role = Role.Tab
                        contentDescription = queueDescription
                    }
                    .testTag("audio-tab-queue"),
        ) {
            Icon(
                painterResource(LucideR.drawable.lucide_ic_list_music),
                contentDescription = queueDescription,
                modifier = Modifier.size(28.dp),
                tint =
                    if (selectedPage == 1) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = onLyricsClick,
            modifier =
                Modifier.size(52.dp)
                    .semantics {
                        role = Role.Tab
                        contentDescription = lyricsDescription
                    }
                    .testTag("audio-tab-lyrics"),
        ) {
            Icon(
                painterResource(LucideR.drawable.lucide_ic_mic_vocal),
                contentDescription = lyricsDescription,
                modifier = Modifier.size(28.dp),
                tint =
                    if (selectedPage == 2) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NowPlayingLandscape(
    state: AudioPlayerState,
    current: MediaItem,
    session: AuthSession,
    timerMode: PlaybackTimeDisplayMode,
    palette: ArtworkPalette,
    onToggleTimer: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onFavorite: (MediaItem) -> Unit,
    coordinator: AudioPlayerCoordinator,
    showLyrics: Boolean,
    lyrics: AudioLyrics?,
    lyricsLoading: Boolean,
    lyricsError: Boolean,
    onRetryLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = palette.accent
    val displayDurationSeconds = resolvedAudioDurationSeconds(state, current)
    val displayPositionSeconds =
        state.positionSeconds.coerceAtLeast(0L).let { position ->
            if (displayDurationSeconds > 0L) position.coerceAtMost(displayDurationSeconds)
            else position
        }
    val timerDescription =
        stringResource(
            if (timerMode == PlaybackTimeDisplayMode.Remaining) {
                R.string.player_show_elapsed_time
            } else {
                R.string.player_show_remaining_time
            }
        )
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxHeight < 420.dp
        val controlSize = if (compact) 46.dp else 56.dp
        val playButtonSize = if (compact) 70.dp else 96.dp
        val horizontalPadding = if (maxWidth < 700.dp) 20.dp else 36.dp
        Column(Modifier.fillMaxSize().padding(horizontal = horizontalPadding, vertical = 8.dp)) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                val artworkMaxHeight = (maxHeight - 24.dp).coerceAtLeast(1.dp)
                val artworkMaxWidth = (maxWidth * .34f).coerceAtLeast(1.dp)
                val artworkSize =
                    minOf(
                            artworkMaxHeight,
                            artworkMaxWidth,
                            if (compact) 240.dp else 360.dp,
                        )
                        .coerceAtLeast(1.dp)
                Row(
                    Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.weight(.38f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (showLyrics) {
                            LyricsPanel(
                                modifier = Modifier.size(artworkSize).padding(8.dp),
                                trackKey = current.id,
                                lyrics = lyrics,
                                loading = lyricsLoading,
                                error = lyricsError,
                                positionSeconds = state.positionSeconds,
                                positionMillis = state.positionMillis,
                                positionUpdatedAtElapsedRealtime =
                                    state.positionUpdatedAtElapsedRealtime,
                                isPlaying = state.isPlaying,
                                accent = accent,
                                onSeek = coordinator::seekTo,
                                onRetry = onRetryLyrics,
                            )
                        } else {
                            Surface(
                                modifier = Modifier.size(artworkSize),
                                shape = RoundedCornerShape(18.dp),
                                color = palette.surface,
                            ) {
                                MusicArtwork(
                                    current,
                                    session,
                                    modifier = Modifier.fillMaxSize(),
                                    contentDescription = current.name,
                                    requestedSize = 512,
                                    shape = RoundedCornerShape(18.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(if (compact) 16.dp else 32.dp))
                    Column(
                        Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                Text(
                                    current.name,
                                    style =
                                        MaterialTheme.typography.headlineSmall.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                                )
                                Text(
                                    current.albumArtist
                                        ?: current.artists.firstOrNull()
                                        ?: stringResource(R.string.audio_unknown_artist),
                                    color = accent,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier =
                                        Modifier.fillMaxWidth().clickable {
                                            current.artistId?.let(onOpenArtist)
                                        },
                                )
                            }
                            IconButton(
                                onClick = { onFavorite(current) },
                                modifier = Modifier.size(if (compact) 48.dp else 56.dp),
                            ) {
                                Icon(
                                    painterResource(LucideR.drawable.lucide_ic_heart),
                                    contentDescription =
                                        stringResource(
                                            if (current.favorite) R.string.remove_favorite
                                            else R.string.add_favorite
                                        ),
                                    tint =
                                        if (current.favorite) accent
                                        else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        AudioQualityLine(state = state, accent = accent)
                        Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
                        AudioProgressScrubber(
                            trackKey = state.currentEntry?.entryId ?: current.id,
                            positionSeconds = displayPositionSeconds,
                            durationSeconds = displayDurationSeconds,
                            accent = accent,
                            onSeek = { coordinator.seekTo(it) },
                        )
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = onToggleTimer),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                if (timerMode == PlaybackTimeDisplayMode.Remaining) {
                                    "-${formatDurationSeconds((displayDurationSeconds - displayPositionSeconds).coerceAtLeast(0L).toDouble())}"
                                } else {
                                    formatDurationSeconds(displayPositionSeconds.toDouble())
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                formatDurationSeconds(displayDurationSeconds.toDouble()),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                                modifier =
                                    Modifier.semantics {
                                        contentDescription = timerDescription
                                    },
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = if (compact) 8.dp else 20.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = coordinator::toggleShuffle,
                                modifier =
                                    Modifier.size(controlSize).testTag("audio-control-shuffle"),
                            ) {
                                Icon(
                                    painterResource(LucideR.drawable.lucide_ic_shuffle),
                                    contentDescription =
                                        stringResource(
                                            if (state.shuffle) R.string.audio_turn_off_shuffle
                                            else R.string.audio_turn_on_shuffle
                                        ),
                                    tint =
                                        if (state.shuffle) accent
                                        else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            IconButton(
                                onClick = coordinator::previous,
                                modifier =
                                    Modifier.size(controlSize).testTag("audio-control-previous"),
                            ) {
                                Icon(
                                    painterResource(LucideR.drawable.lucide_ic_skip_back),
                                    contentDescription =
                                        stringResource(R.string.audio_previous_track),
                                    tint = accent,
                                )
                            }
                            IconButton(
                                onClick = coordinator::togglePlayback,
                                modifier =
                                    Modifier.size(playButtonSize).testTag("audio-control-play"),
                            ) {
                                Surface(
                                    modifier = Modifier.fillMaxSize(),
                                    shape = CircleShape,
                                    color = accent,
                                    contentColor = palette.onAccent,
                                ) {
                                    Box(
                                        Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            painterResource(
                                                if (state.isPlaying)
                                                    LucideR.drawable.lucide_ic_pause
                                                else LucideR.drawable.lucide_ic_play
                                            ),
                                            contentDescription =
                                                stringResource(
                                                    if (state.isPlaying) R.string.pause
                                                    else R.string.play
                                                ),
                                            modifier = Modifier.size(if (compact) 28.dp else 38.dp),
                                            tint = palette.onAccent,
                                        )
                                    }
                                }
                            }
                            IconButton(
                                onClick = coordinator::next,
                                modifier = Modifier.size(controlSize).testTag("audio-control-next"),
                            ) {
                                Icon(
                                    painterResource(LucideR.drawable.lucide_ic_skip_forward),
                                    contentDescription = stringResource(R.string.audio_next_track),
                                    tint = accent,
                                )
                            }
                            IconButton(
                                onClick = coordinator::toggleRepeat,
                                modifier =
                                    Modifier.size(controlSize).testTag("audio-control-repeat"),
                            ) {
                                Icon(
                                    painterResource(repeatIconResource(state.repeatMode)),
                                    contentDescription =
                                        when (state.repeatMode) {
                                            AudioRepeatMode.Off ->
                                                stringResource(R.string.audio_turn_on_repeat)
                                            AudioRepeatMode.Queue ->
                                                stringResource(R.string.audio_repeat_queue)
                                            AudioRepeatMode.Track ->
                                                stringResource(R.string.audio_repeat_track)
                                        },
                                    tint =
                                        if (state.repeatMode != AudioRepeatMode.Off) accent
                                        else MaterialTheme.colorScheme.onSurface,
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
private fun AudioQualityLine(state: AudioPlayerState, accent: Color) {
    val details =
        listOfNotNull(
            state.sourceFormat?.let(::audioFormatLabel),
            state.sourceBitrate?.takeIf { it > 0 }?.let { "${((it + 500) / 1_000)}kbps" },
            state.sourceSampleRate?.takeIf { it > 0 }?.let(::sampleRateLabel),
        )
    val playbackLabel = playbackTypeLabel(state.playbackMode)
    if (details.isEmpty() && playbackLabel == null) return
    Row(
        Modifier.fillMaxWidth().padding(top = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            details.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        playbackLabel?.let { label ->
            Surface(
                color = accent.copy(alpha = .16f),
                contentColor = accent,
                shape = RoundedCornerShape(50),
            ) {
                Text(
                    label.uppercase(Locale.ROOT),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private fun audioFormatLabel(value: String): String {
    val normalized =
        value
            .substringAfterLast('/')
            .substringBefore(';')
            .substringBefore(',')
            .trim()
            .uppercase(Locale.ROOT)
    return when (normalized) {
        "MPEG" -> "MP3"
        "X-MPEGURL",
        "VND.APPLE.MPEGURL",
        "M3U8" -> "HLS"
        else -> normalized
    }
}

private fun sampleRateLabel(value: Int): String {
    val khz = value / 1_000.0
    val formatted = if (value % 1_000 == 0) value / 1_000 else String.format(Locale.US, "%.1f", khz)
    return "${formatted}kHz"
}

@Composable
private fun playbackTypeLabel(mode: String?): String? =
    when (mode?.trim()?.lowercase(Locale.ROOT)) {
        "direct" -> stringResource(R.string.audio_original)
        "remux" -> stringResource(R.string.audio_remuxed)
        "audio-transcode",
        "video-transcode" -> stringResource(R.string.audio_transcoded)
        else -> null
    }

private fun resolvedAudioDurationSeconds(state: AudioPlayerState, current: MediaItem): Long =
    state.durationSeconds.takeIf { it > 0L }
        ?: current.durationSeconds
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.toLong()
            ?.takeIf { it > 0L }
        ?: 0L

/** Artwork-led seek control with a slim rail and an intentional, easy-to-grab handle. */
@Composable
private fun AudioProgressScrubber(
    trackKey: String,
    positionSeconds: Long,
    durationSeconds: Long,
    accent: Color,
    onSeek: (Long) -> Unit,
) {
    val duration = durationSeconds.coerceAtLeast(1L)
    var pendingPositionSeconds by remember(trackKey) { mutableStateOf<Long?>(null) }
    val position = (pendingPositionSeconds ?: positionSeconds).coerceIn(0L, duration)
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
        // A disabled Material slider still draws its own large disabled rail, which makes the
        // recovery state look like a different progress control while duration is unavailable.
        if (durationSeconds > 0) {
            Slider(
                value = position.toFloat(),
                onValueChange = {
                    // Keep the gesture entirely local. Sending a seek command for every pointer
                    // update makes the service rebuild/persist state repeatedly and causes visible
                    // scrub lag.
                    pendingPositionSeconds = it.toLong().coerceIn(0L, duration)
                },
                onValueChangeFinished = {
                    val target = pendingPositionSeconds
                    pendingPositionSeconds = null
                    if (target != null && target != positionSeconds) onSeek(target)
                },
                valueRange = 0f..duration.toFloat(),
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
        } else {
            Spacer(Modifier.fillMaxWidth().height(38.dp))
        }
    }
}

@Immutable
private data class QueuePanelSnapshot(
    val entries: List<AudioQueueEntry>,
    val currentEntryId: String?,
)

@Composable
private fun QueuePanel(
    modifier: Modifier = Modifier,
    snapshot: QueuePanelSnapshot,
    session: AuthSession,
    coordinator: AudioPlayerCoordinator,
    accent: Color,
) {
    val list = snapshot.entries
    val listState = rememberLazyListState()
    // Bounds are consumed only by the long-press drag coroutine. Keeping this map outside the
    // snapshot system avoids invalidating every visible row each time it is measured.
    val rowBounds = remember { mutableMapOf<String, Rect>() }
    var draggedEntryId by remember { mutableStateOf<String?>(null) }
    var draggedFrom by remember { mutableStateOf(-1) }
    var insertionIndex by remember { mutableStateOf(-1) }
    var dragOffsetPx by remember { mutableStateOf(0f) }
    var dragStartCenterY by remember { mutableStateOf(0f) }
    var dragStartCenters by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val rowGapPx = with(density) { 4.dp.toPx() }

    fun clearDrag() {
        draggedEntryId = null
        draggedFrom = -1
        insertionIndex = -1
        dragOffsetPx = 0f
        dragStartCenterY = 0f
        dragStartCenters = emptyMap()
    }

    Column(modifier.fillMaxSize()) {
        if (list.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = coordinator::clearQueue,
                    modifier = Modifier.size(48.dp).testTag("audio-queue-clear"),
                ) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_trash_2),
                        contentDescription = stringResource(R.string.audio_clear_queue),
                        tint = accent,
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(list, key = { _, entry -> entry.entryId }) { index, entry ->
                val isDragged = draggedEntryId == entry.entryId
                val rowStepPx = (rowBounds[entry.entryId]?.height ?: 0f) + rowGapPx
                val targetOffsetPx =
                    when {
                        isDragged -> 0f
                        draggedFrom >= 0 &&
                            insertionIndex > draggedFrom &&
                            index in (draggedFrom + 1)..insertionIndex -> -rowStepPx
                        draggedFrom >= 0 &&
                            insertionIndex in 0 until draggedFrom &&
                            index in insertionIndex until draggedFrom -> rowStepPx
                        else -> 0f
                    }
                val liftProgress by
                    animateFloatAsState(
                        targetValue = if (isDragged) 1f else 0f,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        label = "queue drag lift",
                    )
                val previewAlpha by
                    animateFloatAsState(
                        targetValue = if (isDragged) .96f else 1f,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        label = "queue drag alpha",
                    )
                val shadowElevation by
                    animateDpAsState(
                        targetValue = if (isDragged) 10.dp else 0.dp,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        label = "queue drag shadow",
                    )
                val tonalElevation by
                    animateDpAsState(
                        targetValue = if (isDragged) 4.dp else 0.dp,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        label = "queue drag elevation",
                    )
                val animatedOffsetPx by
                    animateFloatAsState(
                        targetValue = targetOffsetPx,
                        animationSpec =
                            spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        label = "queue row movement",
                    )
                val visualOffsetPx = if (isDragged) dragOffsetPx else animatedOffsetPx
                Surface(
                    modifier =
                        Modifier.fillMaxWidth()
                            .offset { IntOffset(0, visualOffsetPx.roundToInt()) }
                            .zIndex(if (isDragged) 1f else 0f)
                            .shadow(
                                elevation = shadowElevation,
                                shape = RoundedCornerShape(14.dp),
                            )
                            .graphicsLayer {
                                val scale = 1f + (liftProgress * .02f)
                                scaleX = scale
                                scaleY = scale
                            }
                            .onGloballyPositioned { coordinates ->
                                rowBounds[entry.entryId] = coordinates.boundsInRoot()
                            }
                            .pointerInput(entry.entryId) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        val centers = list.mapNotNull { candidate ->
                                            rowBounds[candidate.entryId]?.let {
                                                candidate.entryId to it.center.y
                                            }
                                        }
                                        dragStartCenters = centers.toMap()
                                        draggedEntryId = entry.entryId
                                        draggedFrom = index
                                        insertionIndex = index
                                        dragOffsetPx = 0f
                                        dragStartCenterY =
                                            dragStartCenters[entry.entryId]
                                                ?: rowBounds[entry.entryId]?.center?.y
                                                ?: 0f
                                    },
                                    onDragCancel = ::clearDrag,
                                    onDragEnd = {
                                        val from = draggedFrom
                                        val target = insertionIndex
                                        clearDrag()
                                        if (
                                            from in list.indices &&
                                                target in list.indices &&
                                                target != from
                                        ) {
                                            coordinator.reorderQueue(from, target)
                                        }
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetPx += dragAmount.y
                                        val draggedCenterY = dragStartCenterY + dragOffsetPx
                                        val target =
                                            list.indices
                                                .filter { it != draggedFrom }
                                                .count { candidateIndex ->
                                                    val candidate = list[candidateIndex]
                                                    val centerY =
                                                        dragStartCenters[candidate.entryId]
                                                            ?: rowBounds[candidate.entryId]
                                                                ?.center
                                                                ?.y
                                                            ?: Float.MAX_VALUE
                                                    centerY < draggedCenterY
                                                }
                                        insertionIndex =
                                            target.coerceIn(0, (list.size - 1).coerceAtLeast(0))
                                    },
                                )
                            }
                            .alpha(previewAlpha),
                    shape = RoundedCornerShape(14.dp),
                    color =
                        if (isDragged) {
                            MaterialTheme.colorScheme.surface.copy(alpha = .98f)
                        } else if (entry.entryId == snapshot.currentEntryId) {
                            accent.copy(alpha = .1f)
                        } else Color.Transparent,
                    tonalElevation = tonalElevation,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.weight(1f)
                                .clickable { coordinator.playQueueEntry(entry.entryId) }
                                .testTag("audio-queue-entry-${entry.entryId}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MusicArtwork(
                                item = entry.track,
                                session = session,
                                modifier = Modifier.size(56.dp),
                                contentDescription = entry.track.name,
                                requestedSize = 256,
                                shape = RoundedCornerShape(10.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.track.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    entry.track.albumArtist
                                        ?: entry.track.artists.firstOrNull()
                                        ?: stringResource(R.string.audio_unknown_artist),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(
                            onClick = { coordinator.removeQueueEntry(entry.entryId) },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_x),
                                contentDescription =
                                    stringResource(R.string.audio_remove_from_queue),
                                tint = accent,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricsPanel(
    modifier: Modifier = Modifier,
    trackKey: String,
    lyrics: AudioLyrics?,
    loading: Boolean,
    error: Boolean,
    positionSeconds: Long,
    positionMillis: Long,
    positionUpdatedAtElapsedRealtime: Long,
    isPlaying: Boolean,
    accent: Color,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        loading ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accent)
            }
        error ->
            Column(
                modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.audio_could_not_load_lyrics), color = accent)
                TextButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.textButtonColors(contentColor = accent),
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
        lyrics == null ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.audio_lyrics_unavailable),
                    color = accent.copy(alpha = .72f),
                )
            }
        else -> {
            val listState = rememberLazyListState()
            var followCurrentLine by remember { mutableStateOf(true) }
            var automaticScroll by remember { mutableStateOf(false) }
            var positionedCurrentLine by remember(trackKey, lyrics.timed) { mutableStateOf(false) }
            var currentIndex by
                remember(trackKey) {
                    mutableIntStateOf(
                        lyricIndexAt(
                            lyrics.lines,
                            lyricPositionSeconds(
                                positionSeconds = positionSeconds,
                                positionMillis = positionMillis,
                                positionUpdatedAtElapsedRealtime = positionUpdatedAtElapsedRealtime,
                                isPlaying = isPlaying,
                            ),
                        )
                    )
                }
            val latestPositionSeconds by rememberUpdatedState(positionSeconds)
            val latestPositionMillis by rememberUpdatedState(positionMillis)
            val latestPositionUpdatedAtElapsedRealtime by
                rememberUpdatedState(positionUpdatedAtElapsedRealtime)
            val latestIsPlaying by rememberUpdatedState(isPlaying)
            LaunchedEffect(trackKey, lyrics.timed) {
                if (!lyrics.timed) {
                    currentIndex = -1
                    return@LaunchedEffect
                }
                var anchorPosition = latestPositionSeconds.toDouble().coerceAtLeast(0.0)
                var anchorTime = SystemClock.elapsedRealtime()
                var lastReportedPosition = anchorPosition
                var wasPlaying = latestIsPlaying
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    val playing = latestIsPlaying
                    val precisePosition =
                        lyricPositionSeconds(
                                positionSeconds = latestPositionSeconds,
                                positionMillis = latestPositionMillis,
                                positionUpdatedAtElapsedRealtime =
                                    latestPositionUpdatedAtElapsedRealtime,
                                isPlaying = playing,
                                nowElapsedRealtime = now,
                            )
                            .takeIf { latestPositionUpdatedAtElapsedRealtime > 0L }
                    val reportedPosition =
                        precisePosition ?: latestPositionSeconds.toDouble().coerceAtLeast(0.0)
                    val estimatedPosition =
                        if (precisePosition != null) {
                            reportedPosition
                        } else if (wasPlaying) {
                            anchorPosition + (now - anchorTime).coerceAtLeast(0L) / 1_000.0
                        } else {
                            reportedPosition
                        }
                    val reportedJumped =
                        reportedPosition < lastReportedPosition - .25 ||
                            reportedPosition > lastReportedPosition + 1.25 ||
                            abs(reportedPosition - estimatedPosition) > 1.25
                    if (precisePosition == null && (playing != wasPlaying || reportedJumped)) {
                        anchorPosition = reportedPosition
                        anchorTime = now
                    }
                    val displayPosition =
                        precisePosition
                            ?: if (playing) {
                                anchorPosition +
                                    (SystemClock.elapsedRealtime() - anchorTime).coerceAtLeast(0L) /
                                        1_000.0
                            } else {
                                reportedPosition
                            }
                    val nextIndex = lyricIndexAt(lyrics.lines, displayPosition)
                    if (nextIndex != currentIndex) currentIndex = nextIndex
                    val nextStart =
                        lyrics.lines
                            .asSequence()
                            .drop((nextIndex + 1).coerceAtLeast(0))
                            .mapNotNull(LyricLine::startSeconds)
                            .firstOrNull()
                    val waitMillis =
                        nextStart
                            ?.let { ((it - displayPosition) * 1_000.0).toLong() }
                            ?.coerceIn(32L, 500L) ?: 500L
                    lastReportedPosition = reportedPosition
                    wasPlaying = playing
                    delay(if (playing) waitMillis else 100L)
                }
            }
            LaunchedEffect(listState) {
                snapshotFlow { listState.isScrollInProgress }
                    .collect { scrolling ->
                        if (scrolling && !automaticScroll) followCurrentLine = false
                    }
            }
            LaunchedEffect(currentIndex, followCurrentLine) {
                if (followCurrentLine && currentIndex >= 0) {
                    automaticScroll = true
                    try {
                        if (positionedCurrentLine) {
                            listState.animateScrollToItem(currentIndex)
                        } else {
                            listState.scrollToItem(currentIndex)
                            positionedCurrentLine = true
                        }
                    } finally {
                        automaticScroll = false
                    }
                }
            }
            Column(modifier.fillMaxSize()) {
                if (!followCurrentLine && lyrics.timed) {
                    TextButton(
                        onClick = { followCurrentLine = true },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                        colors = ButtonDefaults.textButtonColors(contentColor = accent),
                    ) {
                        Text(stringResource(R.string.audio_follow_current_line))
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(lyrics.lines, key = { index, _ -> "lyric-$index" }) { index, line
                        ->
                        val isCurrent = index == currentIndex
                        val targetTextStyle =
                            if (isCurrent) {
                                MaterialTheme.typography.titleLarge
                            } else {
                                MaterialTheme.typography.bodyLarge
                            }
                        val animatedFontSize by
                            animateFloatAsState(
                                targetValue = targetTextStyle.fontSize.value,
                                animationSpec =
                                    tween(
                                        durationMillis = 320,
                                        easing = FastOutSlowInEasing,
                                    ),
                                label = "lyric-font-size",
                            )
                        val animatedColor by
                            animateColorAsState(
                                targetValue =
                                    if (isCurrent) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                animationSpec =
                                    tween(
                                        durationMillis = 320,
                                        easing = FastOutSlowInEasing,
                                    ),
                                label = "lyric-color",
                            )
                        val animatedAlpha by
                            animateFloatAsState(
                                targetValue = if (isCurrent) 1f else .72f,
                                animationSpec =
                                    tween(
                                        durationMillis = 320,
                                        easing = FastOutSlowInEasing,
                                    ),
                                label = "lyric-emphasis",
                            )
                        Text(
                            line.text,
                            style =
                                MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = animatedFontSize.sp,
                                    fontWeight =
                                        if (isCurrent) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Medium
                                        },
                                ),
                            color = animatedColor,
                            modifier =
                                Modifier.fillMaxWidth().alpha(animatedAlpha).clickable {
                                    line.startSeconds?.toLong()?.let(onSeek)
                                },
                        )
                    }
                }
            }
        }
    }
}

private fun lyricIndexAt(lines: List<LyricLine>, positionSeconds: Double): Int =
    lines.indexOfLast { line ->
        line.startSeconds?.let { startSeconds -> startSeconds <= positionSeconds } == true
    }

private fun lyricPositionSeconds(
    positionSeconds: Long,
    positionMillis: Long,
    positionUpdatedAtElapsedRealtime: Long,
    isPlaying: Boolean,
    nowElapsedRealtime: Long = SystemClock.elapsedRealtime(),
): Double {
    if (positionUpdatedAtElapsedRealtime <= 0L) {
        return positionSeconds.toDouble().coerceAtLeast(0.0)
    }
    val elapsedSeconds =
        if (isPlaying) {
            (nowElapsedRealtime - positionUpdatedAtElapsedRealtime).coerceAtLeast(0L) / 1_000.0
        } else {
            0.0
        }
    return positionMillis.coerceAtLeast(0L) / 1_000.0 + elapsedSeconds
}

@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zenstream.zenstreammobile.offline

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.audio.AudioPlayerCoordinator
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.model.AudioLyrics
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.PlaybackTimeDisplayMode
import com.zenstream.zenstreammobile.ui.components.decodeBlurHashBitmap
import com.zenstream.zenstreammobile.ui.components.musicArtworkPalette
import com.zenstream.zenstreammobile.ui.screens.AudioMiniPlayerContent
import com.zenstream.zenstreammobile.ui.screens.NowPlayingInnerTabs
import com.zenstream.zenstreammobile.ui.screens.NowPlayingMain
import com.zenstream.zenstreammobile.ui.screens.QueuePanel
import com.zenstream.zenstreammobile.ui.screens.QueuePanelSnapshot
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun MediaItem.isOfflineMusic(): Boolean =
    type in setOf("Audio", "MusicAlbum", "MusicArtist")

@Composable
internal fun OfflineMusicMiniPlayer(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    onOpen: (MediaItem) -> Unit,
) {
    val context = LocalContext.current
    val controller = remember(profile.id) { AudioPlayerCoordinator(context, profile) }
    DisposableEffect(controller) { onDispose { controller.release() } }
    val state by controller.state.collectAsState()
    val session by runtime.sessionStore.session.collectAsState(initial = null)
    val revision by runtime.catalogRevision.collectAsState()
    val scope = rememberCoroutineScope()
    val item = state.currentEntry?.track ?: return
    if (state.offlineProfileId != profile.id) return

    AudioMiniPlayerContent(
        state = state,
        session = session,
        onOpenNowPlaying = { onOpen(item) },
        onFavorite = { selected ->
            scope.launch {
                val favorite = !selected.favorite
                runtime.playbackState.setFavorite(profile.id, selected.id, favorite)
                controller.updateFavorite(selected.id, favorite)
            }
        },
        onStop = controller::stopAndClear,
        onTogglePlayback = controller::togglePlayback,
        artworkContent = { track, modifier ->
            OfflineMusicArtwork(
                runtime = runtime,
                profile = profile,
                item = track,
                revision = revision,
                modifier = modifier,
                cornerRadius = 12.dp,
            )
        },
    )
}

@Composable
internal fun OfflineSavedMusicScreen(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    root: MediaItem,
    onBack: () -> Unit,
    onGoOnline: () -> Unit,
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
    val session by runtime.sessionStore.session.collectAsState(initial = null)
    val connection by runtime.connection.state.collectAsState()
    val revision by runtime.catalogRevision.collectAsState()
    val timeStore = remember(context) { SessionStore(context.applicationContext) }
    val timerMode by
        timeStore.playbackTimeDisplayMode.collectAsState(
            initial = PlaybackTimeDisplayMode.Remaining
        )
    val scope = rememberCoroutineScope()
    val goOnline: () -> Unit = {
        scope.launch {
            runtime.connection.setMode(ConnectionMode.AUTOMATIC)
            onGoOnline()
        }
    }
    var offset by remember(root.id) { mutableIntStateOf(0) }
    var rows by remember(root.id) { mutableStateOf(emptyList<MediaItem>()) }
    var failed by remember(root.id) { mutableStateOf(false) }
    var downloaded by remember(root.id) { mutableStateOf(emptySet<String>()) }
    var innerPage by remember(root.id) { mutableIntStateOf(0) }
    var lyrics by remember(root.id) { mutableStateOf<AudioLyrics?>(null) }
    var lyricsLoading by remember(root.id) { mutableStateOf(false) }
    var lyricsFailed by remember(root.id) { mutableStateOf(false) }
    var lyricsRetry by remember(root.id) { mutableIntStateOf(0) }
    val isTrack = root.type == "Audio"
    val current = state.currentEntry?.track?.takeIf { state.offlineProfileId == profile.id }
    val palette =
        remember(current?.id, current?.imageBlurHashes) {
            current?.let(::musicArtworkPalette) ?: musicArtworkPalette(root)
        }
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
    LaunchedEffect(profile.id, root.id, isTrack) {
        if (isTrack && (initialQueue != null || state.currentEntry?.track?.id != root.id)) {
            val queue = initialQueue ?: listOf(root)
            controller.playTracks(queue, queue.indexOfFirst { it.id == root.id }.coerceAtLeast(0))
        }
    }
    LaunchedEffect(profile.id, current?.id, revision, lyricsRetry) {
        if (current != null) {
            lyricsLoading = true
            lyricsFailed = false
            try {
                lyrics = runtime.audio.lyrics(profile.id, current.id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                lyrics = null
                lyricsFailed = true
            } finally {
                lyricsLoading = false
            }
        } else {
            lyrics = null
            lyricsLoading = false
            lyricsFailed = false
        }
    }

    if (isTrack && current != null) {
        OfflineNowPlayingScreen(
            runtime = runtime,
            profile = profile,
            current = current,
            state = state,
            session = session,
            connection = connection,
            controller = controller,
            onBack = onBack,
            onGoOnline = goOnline,
            onOpenItem = { id ->
                scope.launch { runtime.catalog.item(profile.id, id)?.let { onItem(it, null) } }
            },
            onFavorite = { selected ->
                scope.launch {
                    val favorite = !selected.favorite
                    runtime.playbackState.setFavorite(profile.id, selected.id, favorite)
                    controller.updateFavorite(selected.id, favorite)
                }
            },
            timerMode = timerMode,
            onToggleTimer = {
                scope.launch { timeStore.savePlaybackTimeDisplayMode(timerMode.toggled()) }
            },
            innerPage = innerPage,
            onInnerPageChange = { innerPage = it },
            lyrics = lyrics,
            lyricsLoading = lyricsLoading,
            lyricsError = lyricsFailed,
            onRetryLyrics = { lyricsRetry += 1 },
            revision = revision,
        )
    } else {
        val browsePalette = remember(root.id, root.imageBlurHashes) { musicArtworkPalette(root) }
        Column(
            Modifier.fillMaxSize()
                .background(browsePalette.background)
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
                    root.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (connection.mode == ConnectionMode.OFFLINE) {
                    TextButton(onClick = goOnline) {
                        Text(stringResource(R.string.offline_go_online), maxLines = 1)
                    }
                }
            }
            if (failed)
                Text(
                    stringResource(R.string.offline_request_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            val tracks = rows.filter { it.type == "Audio" }
            if (tracks.isNotEmpty())
                TextButton(
                    enabled = tracks.any { it.id in downloaded },
                    onClick = {
                        controller.playTracks(
                            tracks,
                            tracks.indexOfFirst { it.id in downloaded }.coerceAtLeast(0),
                        )
                    },
                ) {
                    Text(stringResource(R.string.offline_queue_this_page, tracks.size))
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
                        leadingContent = {
                            OfflineMusicArtwork(
                                runtime = runtime,
                                profile = profile,
                                item = item,
                                revision = revision,
                                modifier = Modifier.size(52.dp),
                                cornerRadius = 10.dp,
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                onItem(item, tracks.takeIf { item.type == "Audio" })
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
            OfflineMusicMiniPlayer(runtime, profile) { onItem(it, null) }
        }
    }
}

@Composable
private fun OfflineNowPlayingScreen(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    current: MediaItem,
    state: com.zenstream.zenstreammobile.model.AudioPlayerState,
    session: com.zenstream.zenstreammobile.model.AuthSession?,
    connection: ConnectionState,
    controller: AudioPlayerCoordinator,
    onBack: () -> Unit,
    onGoOnline: () -> Unit,
    onOpenItem: (String) -> Unit,
    onFavorite: (MediaItem) -> Unit,
    timerMode: PlaybackTimeDisplayMode,
    onToggleTimer: () -> Unit,
    innerPage: Int,
    onInnerPageChange: (Int) -> Unit,
    lyrics: AudioLyrics?,
    lyricsLoading: Boolean,
    lyricsError: Boolean,
    onRetryLyrics: () -> Unit,
    revision: Long,
) {
    val palette = remember(current.id, current.imageBlurHashes) { musicArtworkPalette(current) }
    val backgroundBlurHash =
        remember(current.id, current.imageBlurHashes["Primary"]) {
            current.imageBlurHashes["Primary"]
                ?.takeIf(String::isNotBlank)
                ?.let(::decodeBlurHashBitmap)
        }
    val streamAvailable =
        connection.mode == ConnectionMode.AUTOMATIC &&
            session?.userId == profile.account &&
            session.serverUrl == profile.serverUrl

    Box(Modifier.fillMaxSize().background(palette.background)) {
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
                current.album?.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.audio_now_playing)
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (!queuePage) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        stringResource(R.string.audio_playing_from_album),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                    Text(
                                        pageTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow =
                                            androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        modifier =
                                            Modifier.clickable {
                                                current.albumId?.let(onOpenItem)
                                            },
                                    )
                                }
                            } else {
                                Text(
                                    pageTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (queuePage) onInnerPageChange(0) else onBack()
                            },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painterResource(
                                    if (queuePage) LucideR.drawable.lucide_ic_arrow_left
                                    else LucideR.drawable.lucide_ic_chevron_down
                                ),
                                contentDescription =
                                    stringResource(
                                        if (queuePage) R.string.audio_back_to_now_playing
                                        else R.string.audio_close_now_playing
                                    ),
                                tint = palette.accent,
                            )
                        }
                    },
                    actions = {
                        when {
                            connection.mode == ConnectionMode.OFFLINE ->
                                TextButton(onClick = onGoOnline) {
                                    Text(stringResource(R.string.offline_go_online), maxLines = 1)
                                }
                            streamAvailable ->
                                TextButton(onClick = controller::streamCurrent) {
                                    Text(
                                        stringResource(R.string.offline_stream_track),
                                        maxLines = 1,
                                    )
                                }
                            else -> Spacer(Modifier.size(48.dp))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
            },
            containerColor = Color.Transparent,
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (queuePage) {
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
                            coordinator = controller,
                            accent = palette.accent,
                            artworkContent = { item, modifier ->
                                OfflineMusicArtwork(
                                    runtime,
                                    profile,
                                    item,
                                    revision,
                                    modifier,
                                    cornerRadius = 10.dp,
                                )
                            },
                        )
                    } else {
                        NowPlayingMain(
                            state = state,
                            current = current,
                            session = session,
                            timerMode = timerMode,
                            palette = palette,
                            onToggleTimer = onToggleTimer,
                            onOpenArtist = onOpenItem,
                            onFavorite = onFavorite,
                            coordinator = controller,
                            showLyrics = innerPage == 2,
                            lyrics = lyrics,
                            lyricsLoading = lyricsLoading,
                            lyricsError = lyricsError,
                            onRetryLyrics = onRetryLyrics,
                            artworkContent = { item, modifier ->
                                OfflineMusicArtwork(
                                    runtime,
                                    profile,
                                    item,
                                    revision,
                                    modifier,
                                    cornerRadius = 18.dp,
                                )
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (!queuePage && !state.error.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .9f),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        modifier =
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                state.error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = controller::retry,
                                colors =
                                    ButtonDefaults.textButtonColors(contentColor = palette.accent),
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                }
                NowPlayingInnerTabs(
                    selectedPage = innerPage,
                    accent = palette.accent,
                    onQueueClick = { onInnerPageChange(if (innerPage == 1) 0 else 1) },
                    onLyricsClick = { onInnerPageChange(if (innerPage == 2) 0 else 2) },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun OfflineMusicArtwork(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    item: MediaItem,
    revision: Long,
    modifier: Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp,
) {
    var artwork by remember(profile.id, item.id) { mutableStateOf<File?>(null) }
    LaunchedEffect(profile.id, item.id, revision) {
        artwork =
            runtime.audio.artwork(profile.id, item.id)
                ?: item.albumId?.let { runtime.audio.artwork(profile.id, it) }
    }
    val palette = remember(item.id, item.imageBlurHashes) { musicArtworkPalette(item) }
    val blurHash =
        remember(item.id, item.imageBlurHashes["Primary"]) {
            item.imageBlurHashes["Primary"]?.takeIf(String::isNotBlank)?.let(::decodeBlurHashBitmap)
        }
    Box(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius))
            .background(palette.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (blurHash != null) {
            Image(
                bitmap = blurHash,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (artwork != null) {
            AsyncImage(
                model = artwork,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (blurHash == null) {
            Text(
                "♪",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f),
            )
        }
    }
}

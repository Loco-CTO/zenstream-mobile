@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zenstream.zenstreammobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.MusicDataSource
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MusicAlbumData
import com.zenstream.zenstreammobile.model.MusicArtistData
import com.zenstream.zenstreammobile.ui.MusicAlbumViewModel
import com.zenstream.zenstreammobile.ui.MusicArtistViewModel
import com.zenstream.zenstreammobile.ui.components.AudioCard
import com.zenstream.zenstreammobile.ui.components.AudioTrackRow
import com.zenstream.zenstreammobile.ui.components.MusicArtistCard
import com.zenstream.zenstreammobile.ui.components.MusicArtwork
import com.zenstream.zenstreammobile.ui.components.MusicCreditLine
import com.zenstream.zenstreammobile.ui.components.musicArtworkPalette

private val MUSIC_DETAIL_TOP_CONTENT_PADDING = 96.dp

@Composable
fun MusicAlbumScreen(
    repository: MusicDataSource,
    session: AuthSession,
    albumId: String,
    selectedTrackId: String? = null,
    currentTrackId: String? = null,
    outerPadding: PaddingValues = PaddingValues(),
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onPlayTracks: (List<MediaItem>, Int, Boolean) -> Unit,
    onAddToQueue: (List<MediaItem>) -> Unit,
    onScrollabilityChanged: (Boolean) -> Unit = {},
) {
    val vm: MusicAlbumViewModel =
        viewModel(
            key = "music-album-${session.userId}-${session.token}-$albumId",
            factory = MusicAlbumViewModel.Factory(repository, session, albumId),
        )
    val state by vm.uiState.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    var detailScrolled by remember(albumId) { mutableStateOf(false) }
    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(outerPadding)
    ) {
        when {
            state.loading && state.data == null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            state.error && state.data == null ->
                MusicErrorState(
                    Modifier.fillMaxSize(),
                    stringResource(R.string.music_album_load_failed),
                    vm::load,
                )
            state.data != null ->
                AlbumContent(
                    data = state.data!!,
                    session = session,
                    selectedTrackId = selectedTrackId,
                    padding = PaddingValues(),
                    onArtistClick = onOpenArtist,
                    onAlbumClick = onOpenAlbum,
                    onPlayTracks = onPlayTracks,
                    onAddToQueue = onAddToQueue,
                    onFavoriteAlbum = vm::toggleFavorite,
                    onFavoriteTrack = { vm.toggleTrackFavorite(it.id) },
                    currentTrackId = currentTrackId,
                    onScrollabilityChanged = onScrollabilityChanged,
                    onDetailScrolled = { detailScrolled = it },
                )
        }

        DetailOverlayTopBar(
            title = state.data?.album?.name ?: stringResource(R.string.music_album),
            visible = true,
            scrolled = detailScrolled,
            backOnly = state.data == null,
            onBack = onBack,
        )
    }
}

@Composable
private fun AlbumContent(
    data: MusicAlbumData,
    session: AuthSession,
    selectedTrackId: String?,
    padding: PaddingValues,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlayTracks: (List<MediaItem>, Int, Boolean) -> Unit,
    onAddToQueue: (List<MediaItem>) -> Unit,
    onFavoriteAlbum: () -> Unit,
    onFavoriteTrack: (MediaItem) -> Unit,
    currentTrackId: String?,
    onScrollabilityChanged: (Boolean) -> Unit,
    onDetailScrolled: (Boolean) -> Unit = {},
) {
    val listState = rememberLazyListState()
    LaunchedEffect(data.album.id) {
        onDetailScrolled(false)
        listState.scrollToItem(0)
    }
    ObserveDetailScroll(listState, onDetailScrolled)
    val accent =
        remember(data.album.id, data.album.imageBlurHashes["Primary"]) {
            musicArtworkPalette(data.album).accent
        }
    val tracks =
        remember(data.tracks) {
            data.tracks
                .distinctBy { it.id }
                .sortedWith(
                    compareBy<MediaItem> { it.discNumber ?: 1 }
                        .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                        .thenBy { it.name.lowercase() }
                )
        }
    val grouped = remember(tracks) { tracks.groupBy { it.discNumber ?: 1 }.toSortedMap() }
    val duration = remember(tracks) { tracks.sumOf { it.durationSeconds ?: 0.0 } }
    ObserveScrollability(
        canScroll = { listState.canScrollForward || listState.canScrollBackward },
        onScrollabilityChanged = onScrollabilityChanged,
    )
    val selectedListIndex =
        remember(selectedTrackId, tracks) {
            albumTrackListIndex(grouped, selectedTrackId)
        }
    LaunchedEffect(selectedTrackId, selectedListIndex) {
        if (selectedListIndex >= 0) listState.animateScrollToItem(selectedListIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "album-header") {
            AlbumHeader(
                data = data,
                session = session,
                onArtistClick = onArtistClick,
                onPlay = { onPlayTracks(tracks, 0, false) },
                onShuffle = { onPlayTracks(tracks, 0, true) },
                onFavorite = onFavoriteAlbum,
                onAddToQueue = { onAddToQueue(tracks) },
            )
        }
        grouped.forEach { (disc, discTracks) ->
            if (grouped.size > 1) {
                item(key = "disc-$disc") {
                    Text(
                        stringResource(R.string.music_disc, disc),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
            itemsIndexed(discTracks, key = { _, track -> "track-${track.id}" }) { _, track ->
                AudioTrackRow(
                    item = track,
                    isCurrent = track.id == currentTrackId,
                    onClick = {
                        onPlayTracks(
                            tracks,
                            tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0),
                            false,
                        )
                    },
                    onFavorite = onFavoriteTrack,
                    accentColor = accent,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        if (data.album.genres.isNotEmpty() || !data.album.overview.isNullOrBlank()) {
            item(key = "album-details") {
                AlbumDetails(data.album)
            }
        }
        item(key = "album-footer") {
            AlbumFooter(
                album = data.album,
                trackCount = tracks.size,
                durationSeconds = duration,
            )
        }
        if (data.relatedAlbums.isNotEmpty()) {
            item(key = "related-albums") {
                MusicSection(
                    title = stringResource(R.string.music_related_albums),
                    items = data.relatedAlbums,
                    session = session,
                    onClick = onAlbumClick,
                )
            }
        }
    }
}

@Composable
private fun AlbumHeader(
    data: MusicAlbumData,
    session: AuthSession,
    onArtistClick: (String) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onFavorite: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    val album = data.album
    val year =
        album.releaseDate?.take(4)?.takeIf { it.length == 4 } ?: album.productionYear?.toString()
    val palette =
        remember(album.id, album.imageBlurHashes["Primary"]) { musicArtworkPalette(album) }
    val accent = palette.accent
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(320.dp + MUSIC_DETAIL_TOP_CONTENT_PADDING)
        ) {
            Box(
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                palette.background.copy(alpha = .92f),
                                palette.surface.copy(alpha = .62f),
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.background,
                            )
                        )
                    )
            )
            val artworkSize = (maxWidth - 32.dp).coerceAtMost(272.dp).coerceAtLeast(0.dp)
            Box(
                modifier = Modifier.fillMaxSize().padding(top = MUSIC_DETAIL_TOP_CONTENT_PADDING),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.size(artworkSize),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    MusicArtwork(
                        album,
                        session,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = album.name,
                        requestedSize = 560,
                        shape = RoundedCornerShape(18.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                album.name,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            MusicCreditLine(
                album,
                data.artist,
                onArtistClick,
                modifier = Modifier.fillMaxWidth(),
                accentColor = accent,
            )
            Text(
                listOfNotNull(
                        year,
                        album.albumType?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.music_album),
                    )
                    .joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onFavorite, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_heart),
                        contentDescription =
                            stringResource(
                                if (album.favorite) R.string.remove_favorite
                                else R.string.add_favorite
                            ),
                        tint = if (album.favorite) accent else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onAddToQueue, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_list_plus),
                        contentDescription = stringResource(R.string.music_add_album_to_queue),
                        tint = accent,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onShuffle, modifier = Modifier.size(44.dp)) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_shuffle),
                        contentDescription = stringResource(R.string.music_shuffle_album),
                        tint = accent,
                    )
                }
                IconButton(onClick = onPlay, modifier = Modifier.size(54.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape,
                        color = accent,
                        contentColor = palette.onAccent,
                    ) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_play),
                            contentDescription = stringResource(R.string.music_play_album),
                            modifier = Modifier.padding(15.dp),
                            tint = palette.onAccent,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumFooter(
    album: MediaItem,
    trackCount: Int,
    durationSeconds: Double,
) {
    val summary = buildList {
        if (trackCount > 0) {
            add(stringResource(R.string.music_album_track_count, trackCount))
        }
        formatAlbumDuration(durationSeconds)?.let { add(it) }
    }
    val label = album.label?.trim()?.takeIf(String::isNotBlank)
    if (summary.isEmpty() && label == null) return

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (summary.isNotEmpty()) {
            Text(
                summary.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        label?.let {
            Text(
                "© $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun formatAlbumDuration(seconds: Double): String? {
    if (!seconds.isFinite() || seconds <= 0.0) return null
    val totalMinutes = (seconds / 60.0).toInt()
    if (totalMinutes <= 0) return stringResource(R.string.music_album_duration_one_minute)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 ->
            stringResource(R.string.music_album_duration_hours_minutes, hours, minutes)
        hours > 0 -> pluralStringResource(R.plurals.music_album_duration_hours, hours, hours)
        else -> pluralStringResource(R.plurals.music_album_duration_minutes, minutes, minutes)
    }
}

@Composable
private fun AlbumDetails(album: MediaItem) {
    val palette =
        remember(album.id, album.imageBlurHashes["Primary"]) { musicArtworkPalette(album) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (album.genres.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(album.genres.distinct().take(8), key = { it }) { genre ->
                    AssistChip(
                        onClick = {},
                        label = { Text(genre, maxLines = 1) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = palette.accent),
                        border = BorderStroke(1.dp, palette.accent.copy(alpha = .46f)),
                    )
                }
            }
        }
        album.overview?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun MusicArtistScreen(
    repository: MusicDataSource,
    session: AuthSession,
    artistId: String,
    outerPadding: PaddingValues = PaddingValues(),
    onBack: () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onPlayTracks: (List<MediaItem>, Int, Boolean) -> Unit,
    onAddToQueue: (List<MediaItem>) -> Unit,
    onShuffleTracks: (List<MediaItem>) -> Unit = {},
    currentTrackId: String? = null,
    onScrollabilityChanged: (Boolean) -> Unit = {},
) {
    val vm: MusicArtistViewModel =
        viewModel(
            key = "music-artist-${session.userId}-${session.token}-$artistId",
            factory = MusicArtistViewModel.Factory(repository, session, artistId),
        )
    val state by vm.uiState.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    var detailScrolled by remember(artistId) { mutableStateOf(false) }
    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(outerPadding)
    ) {
        when {
            state.loading && state.data == null ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            state.error && state.data == null ->
                MusicErrorState(
                    Modifier.fillMaxSize(),
                    stringResource(R.string.music_artist_load_failed),
                    vm::load,
                )
            state.data != null ->
                ArtistContent(
                    data = state.data!!,
                    session = session,
                    padding = PaddingValues(),
                    tracksLoading = state.tracksLoading,
                    tracksError = state.tracksError,
                    onFollow = vm::toggleFollowing,
                    onFavorite = vm::toggleFavorite,
                    onPlayAll = { vm.playAll { tracks -> onPlayTracks(tracks, 0, false) } },
                    onShuffleAll = { vm.playAll(onShuffleTracks) },
                    onArtistClick = onOpenArtist,
                    onAlbumClick = onOpenAlbum,
                    onTrackClick = { tracks, index -> onPlayTracks(tracks, index, false) },
                    onAddToQueue = onAddToQueue,
                    currentTrackId = currentTrackId,
                    onScrollabilityChanged = onScrollabilityChanged,
                    onDetailScrolled = { detailScrolled = it },
                )
        }

        DetailOverlayTopBar(
            title = state.data?.artist?.name ?: stringResource(R.string.music_artist),
            visible = true,
            scrolled = detailScrolled,
            backOnly = state.data == null,
            onBack = onBack,
        )
    }
}

@Composable
private fun ArtistContent(
    data: MusicArtistData,
    session: AuthSession,
    padding: PaddingValues,
    tracksLoading: Boolean,
    tracksError: Boolean,
    onFollow: () -> Unit,
    onFavorite: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onTrackClick: (List<MediaItem>, Int) -> Unit,
    onAddToQueue: (List<MediaItem>) -> Unit,
    currentTrackId: String?,
    onScrollabilityChanged: (Boolean) -> Unit,
    onDetailScrolled: (Boolean) -> Unit = {},
) {
    val artist = data.artist
    val tracks = remember(data.tracks) { data.tracks.distinctBy { it.id } }
    val albums = remember(data.albums) { data.albums.distinctBy { it.id } }
    val palette =
        remember(artist.id, artist.imageBlurHashes["Primary"]) { musicArtworkPalette(artist) }
    val accent = palette.accent
    val listState = rememberLazyListState()
    LaunchedEffect(artist.id) {
        onDetailScrolled(false)
        listState.scrollToItem(0)
    }
    ObserveDetailScroll(listState, onDetailScrolled)
    ObserveScrollability(
        canScroll = { listState.canScrollForward || listState.canScrollBackward },
        onScrollabilityChanged = onScrollabilityChanged,
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item(key = "artist-header") {
            Column(Modifier.fillMaxWidth()) {
                BoxWithConstraints(
                    modifier =
                        Modifier.fillMaxWidth().height(320.dp + MUSIC_DETAIL_TOP_CONTENT_PADDING)
                ) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        palette.background.copy(alpha = .92f),
                                        palette.surface.copy(alpha = .58f),
                                        MaterialTheme.colorScheme.background,
                                        MaterialTheme.colorScheme.background,
                                    )
                                )
                            )
                    )
                    val artworkSize = (maxWidth - 32.dp).coerceAtMost(272.dp).coerceAtLeast(0.dp)
                    Box(
                        modifier =
                            Modifier.fillMaxSize().padding(top = MUSIC_DETAIL_TOP_CONTENT_PADDING),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            modifier = Modifier.size(artworkSize),
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            MusicArtwork(
                                artist,
                                session,
                                modifier = Modifier.fillMaxSize(),
                                contentDescription = artist.name,
                                requestedSize = 560,
                                shape = RoundedCornerShape(22.dp),
                                fallbackGlyph = "★",
                            )
                        }
                    }
                }
                Column(
                    Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    Text(
                        artist.name,
                        style = MaterialTheme.typography.headlineLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(R.string.music_artist_summary, albums.size, data.trackCount),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onFavorite, modifier = Modifier.size(48.dp)) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_heart),
                                contentDescription =
                                    stringResource(
                                        if (artist.favorite) R.string.remove_favorite
                                        else R.string.add_favorite
                                    ),
                                tint =
                                    if (artist.favorite) accent
                                    else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        OutlinedButton(
                            onClick = onFollow,
                            modifier = Modifier.height(44.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                            border = BorderStroke(1.dp, accent.copy(alpha = .62f)),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                        ) {
                            Text(
                                if (artist.following == true) {
                                    stringResource(R.string.music_following)
                                } else {
                                    stringResource(R.string.follow)
                                },
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = onShuffleAll,
                            enabled = !tracksLoading,
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_shuffle),
                                contentDescription =
                                    stringResource(R.string.music_shuffle_artist_tracks),
                                tint =
                                    if (!tracksLoading) accent
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = onPlayAll,
                            enabled = !tracksLoading,
                            modifier = Modifier.size(54.dp),
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = CircleShape,
                                color = accent,
                                contentColor = palette.onAccent,
                            ) {
                                if (tracksLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(15.dp),
                                        strokeWidth = 2.dp,
                                        color = palette.onAccent,
                                    )
                                } else {
                                    Icon(
                                        painterResource(LucideR.drawable.lucide_ic_play),
                                        contentDescription =
                                            stringResource(R.string.music_play_all_artist_tracks),
                                        modifier = Modifier.padding(15.dp),
                                        tint = palette.onAccent,
                                    )
                                }
                            }
                        }
                    }
                    if (tracksError)
                        Text(
                            stringResource(R.string.music_tracks_load_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                }
            }
        }
        if (tracks.isNotEmpty()) {
            item(key = "artist-top-tracks-header") {
                Text(
                    stringResource(R.string.music_top_tracks),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
            itemsIndexed(tracks.take(5), key = { _, track -> "artist-top-track-${track.id}" }) {
                _,
                track ->
                AudioTrackRow(
                    item = track,
                    isCurrent = track.id == currentTrackId,
                    onClick = {
                        onTrackClick(
                            tracks,
                            tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0),
                        )
                    },
                    accentColor = accent,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        if (artist.genres.isNotEmpty() || !artist.overview.isNullOrBlank()) {
            item(key = "artist-details") {
                ArtistDetails(artist)
            }
        }
        val releaseGroups = albums.groupBy { releaseLabel(it) }
        releaseGroups.forEach { (label, groupedAlbums) ->
            item(key = "artist-release-$label") {
                MusicSection(
                    label.ifBlank { stringResource(R.string.music_albums) },
                    groupedAlbums,
                    session,
                    onAlbumClick,
                )
            }
        }
        if (data.appearsIn.isNotEmpty()) {
            item(key = "artist-appears-in") {
                MusicSection(
                    stringResource(R.string.music_appears_in),
                    data.appearsIn,
                    session,
                    onAlbumClick,
                )
            }
        }
        if (data.relatedArtists.isNotEmpty()) {
            item(key = "artist-related") {
                MusicSection(
                    stringResource(R.string.music_similar_artists),
                    data.relatedArtists,
                    session,
                    onArtistClick,
                    artistItems = true,
                )
            }
        }
        if (tracks.size > 5) {
            item(key = "artist-tracks-header") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.music_tracks),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onAddToQueue(tracks) }) {
                        Text(stringResource(R.string.music_add_to_queue))
                    }
                }
            }
            itemsIndexed(tracks, key = { _, track -> "artist-track-${track.id}" }) { index, track ->
                AudioTrackRow(
                    item = track,
                    isCurrent = track.id == currentTrackId,
                    onClick = { onTrackClick(tracks, index) },
                    accentColor = accent,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ArtistDetails(artist: MediaItem) {
    val palette =
        remember(artist.id, artist.imageBlurHashes["Primary"]) { musicArtworkPalette(artist) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (artist.genres.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(artist.genres.distinct().take(8), key = { it }) { genre ->
                    AssistChip(
                        onClick = {},
                        label = { Text(genre, maxLines = 1) },
                        colors = AssistChipDefaults.assistChipColors(labelColor = palette.accent),
                        border = BorderStroke(1.dp, palette.accent.copy(alpha = .46f)),
                    )
                }
            }
        }
        artist.overview?.takeIf(String::isNotBlank)?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 7,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun releaseLabel(item: MediaItem): String {
    val secondary = item.albumSecondaryTypes.firstOrNull()?.trim().orEmpty()
    return secondary.ifBlank { item.albumType?.trim().orEmpty() }
}

private fun albumTrackListIndex(
    grouped: Map<Int, List<MediaItem>>,
    selectedTrackId: String?,
): Int {
    if (selectedTrackId.isNullOrBlank()) return -1
    var index = 1
    grouped.forEach { (_, discTracks) ->
        if (grouped.size > 1) index += 1
        val selected = discTracks.indexOfFirst { it.id == selectedTrackId }
        if (selected >= 0) return index + selected
        index += discTracks.size
    }
    return -1
}

@Composable
private fun MusicSection(
    title: String,
    items: List<MediaItem>,
    session: AuthSession,
    onClick: (String) -> Unit,
    artistItems: Boolean = false,
) {
    val uniqueItems = items.distinctBy { it.id }
    if (uniqueItems.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(uniqueItems, key = { it.id }) { item ->
                if (artistItems) {
                    MusicArtistCard(item, session, onClick = { onClick(item.id) })
                } else {
                    AudioCard(
                        item = item,
                        session = session,
                        onClick = { onClick(item.id) },
                        width = 156.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicErrorState(modifier: Modifier, message: String, onRetry: () -> Unit) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

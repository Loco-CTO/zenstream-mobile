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
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.R as LucideR
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
import com.zenstream.zenstreammobile.ui.components.formatDurationSeconds
import com.zenstream.zenstreammobile.ui.components.musicArtworkPalette

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
    Scaffold(
        modifier = Modifier.fillMaxSize().padding(outerPadding),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.data?.album?.name ?: "Album",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_arrow_left),
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading && state.data == null ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            state.error && state.data == null ->
                MusicErrorState(
                    Modifier.fillMaxSize().padding(padding),
                    "Could not load this album",
                    vm::load,
                )
            state.data != null ->
                AlbumContent(
                    data = state.data!!,
                    session = session,
                    selectedTrackId = selectedTrackId,
                    padding = padding,
                    onArtistClick = onOpenArtist,
                    onAlbumClick = onOpenAlbum,
                    onPlayTracks = onPlayTracks,
                    onAddToQueue = onAddToQueue,
                    onFavoriteAlbum = vm::toggleFavorite,
                    onFavoriteTrack = { vm.toggleTrackFavorite(it.id) },
                    currentTrackId = currentTrackId,
                    onScrollabilityChanged = onScrollabilityChanged,
                )
        }
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
) {
    val listState = rememberLazyListState()
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
                        "Disc $disc",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
            itemsIndexed(discTracks, key = { _, track -> "track-${track.id}" }) { index, track ->
                AudioTrackRow(
                    item = track,
                    session = session,
                    position = track.trackNumber ?: index + 1,
                    isCurrent = track.id == currentTrackId,
                    onClick = {
                        onPlayTracks(
                            tracks,
                            tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0),
                            false,
                        )
                    },
                    onFavorite = onFavoriteTrack,
                    onArtistClick = onArtistClick,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        if (data.album.genres.isNotEmpty() || !data.album.overview.isNullOrBlank()) {
            item(key = "album-details") {
                AlbumDetails(data.album)
            }
        }
        if (data.relatedAlbums.isNotEmpty()) {
            item(key = "related-albums") {
                MusicSection(
                    title = "Related albums",
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
    val uniqueTracks = data.tracks.distinctBy { it.id }
    val duration = uniqueTracks.sumOf { it.durationSeconds ?: 0.0 }
    val palette =
        remember(album.id, album.imageBlurHashes["Primary"]) { musicArtworkPalette(album) }
    val accent = palette.accent
    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().height(320.dp),
            contentAlignment = Alignment.Center,
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
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                album.albumType?.uppercase() ?: "ALBUM",
                color = accent,
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                album.name,
                style = MaterialTheme.typography.headlineLarge,
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
                        album.label,
                        "${uniqueTracks.size} tracks",
                        formatDurationSeconds(duration),
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
                            if (album.favorite) "Remove favorite" else "Add favorite",
                        tint =
                            if (album.favorite) accent
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onAddToQueue, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_list_filter),
                        contentDescription = "Add album to queue",
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onShuffle, modifier = Modifier.size(48.dp)) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_shuffle),
                        contentDescription = "Shuffle album",
                        tint = accent,
                    )
                }
                IconButton(onClick = onPlay, modifier = Modifier.size(60.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = CircleShape,
                        color = accent,
                        contentColor = palette.onAccent,
                    ) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_play),
                            contentDescription = "Play album",
                            modifier = Modifier.padding(17.dp),
                            tint = palette.onAccent,
                        )
                    }
                }
            }
        }
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
    Scaffold(
        modifier = Modifier.fillMaxSize().padding(outerPadding),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.data?.artist?.name ?: "Artist",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_arrow_left),
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading && state.data == null ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            state.error && state.data == null ->
                MusicErrorState(
                    Modifier.fillMaxSize().padding(padding),
                    "Could not load this artist",
                    vm::load,
                )
            state.data != null ->
                ArtistContent(
                    data = state.data!!,
                    session = session,
                    padding = padding,
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
                )
        }
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
) {
    val artist = data.artist
    val tracks = remember(data.tracks) { data.tracks.distinctBy { it.id } }
    val albums = remember(data.albums) { data.albums.distinctBy { it.id } }
    val palette =
        remember(artist.id, artist.imageBlurHashes["Primary"]) { musicArtworkPalette(artist) }
    val accent = palette.accent
    val listState = rememberLazyListState()
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
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    contentAlignment = Alignment.Center,
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
                        "${albums.size} releases · ${data.trackCount} tracks",
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
                                    if (artist.favorite) "Remove favorite" else "Add favorite",
                                tint =
                                    if (artist.favorite) accent
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
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
                                if (artist.following == true) "Following" else "Follow",
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = onShuffleAll,
                            enabled = !tracksLoading,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_shuffle),
                                contentDescription = "Shuffle artist tracks",
                                tint =
                                    if (!tracksLoading) accent
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = onPlayAll,
                            enabled = !tracksLoading,
                            modifier = Modifier.size(60.dp),
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                shape = CircleShape,
                                color = accent,
                                contentColor = palette.onAccent,
                            ) {
                                if (tracksLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(18.dp),
                                        strokeWidth = 2.dp,
                                        color = palette.onAccent,
                                    )
                                } else {
                                    Icon(
                                        painterResource(LucideR.drawable.lucide_ic_play),
                                        contentDescription = "Play all artist tracks",
                                        modifier = Modifier.padding(17.dp),
                                        tint = palette.onAccent,
                                    )
                                }
                            }
                        }
                    }
                    if (tracksError)
                        Text("Could not load tracks", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (tracks.isNotEmpty()) {
            item(key = "artist-top-tracks-header") {
                Text(
                    "Top tracks",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
            itemsIndexed(tracks.take(5), key = { _, track -> "artist-top-track-${track.id}" }) {
                index,
                track ->
                AudioTrackRow(
                    item = track,
                    session = session,
                    position = track.trackNumber ?: index + 1,
                    isCurrent = track.id == currentTrackId,
                    onClick = {
                        onTrackClick(
                            tracks,
                            tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0),
                        )
                    },
                    onArtistClick = onArtistClick,
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
                MusicSection(label, groupedAlbums, session, onAlbumClick)
            }
        }
        if (data.appearsIn.isNotEmpty()) {
            item(key = "artist-appears-in") {
                MusicSection("Appears in", data.appearsIn, session, onAlbumClick)
            }
        }
        if (data.relatedArtists.isNotEmpty()) {
            item(key = "artist-related") {
                MusicSection(
                    "Similar artists",
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
                        "Tracks",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onAddToQueue(tracks) }) { Text("Add to queue") }
                }
            }
            itemsIndexed(tracks, key = { _, track -> "artist-track-${track.id}" }) { index, track ->
                AudioTrackRow(
                    item = track,
                    session = session,
                    position = track.trackNumber ?: index + 1,
                    isCurrent = track.id == currentTrackId,
                    onClick = { onTrackClick(tracks, index) },
                    onArtistClick = onArtistClick,
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
    return secondary.ifBlank { item.albumType?.ifBlank { null } ?: "Albums" }
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
        Button(onClick = onRetry) { Text("Retry") }
    }
}

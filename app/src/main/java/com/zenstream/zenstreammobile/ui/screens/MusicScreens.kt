@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.zenstream.zenstreammobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.zenstream.zenstreammobile.ui.components.MusicArtwork
import com.zenstream.zenstreammobile.ui.components.MusicCreditLine
import com.zenstream.zenstreammobile.ui.components.formatDurationSeconds

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
        modifier = Modifier.padding(outerPadding),
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
                MusicErrorState(Modifier.fillMaxSize().padding(padding), "Could not load this album", vm::load)
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
                    modifier = Modifier.fillMaxSize(),
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
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val tracks =
        data.tracks
            .distinctBy { it.id }
            .sortedWith(
                compareBy<MediaItem> { it.discNumber ?: 1 }
                    .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                    .thenBy { it.name.lowercase() },
            )
    val grouped = tracks.groupBy { it.discNumber ?: 1 }.toSortedMap()
    ObserveScrollability(
        canScroll = { listState.canScrollForward || listState.canScrollBackward },
        onScrollabilityChanged = onScrollabilityChanged,
    )
    val selectedListIndex = remember(selectedTrackId, tracks) {
        albumTrackListIndex(grouped, selectedTrackId)
    }
    LaunchedEffect(selectedTrackId, selectedListIndex) {
        if (selectedListIndex >= 0) listState.animateScrollToItem(selectedListIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.padding(padding),
        contentPadding = PaddingValues(bottom = 28.dp),
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
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            itemsIndexed(discTracks, key = { _, track -> "track-${track.id}" }) { index, track ->
                AudioTrackRow(
                    item = track,
                    session = session,
                    position = track.trackNumber ?: index + 1,
                    isCurrent = track.id == currentTrackId || track.id == selectedTrackId,
                    onClick = { onPlayTracks(tracks, tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0), false) },
                    onFavorite = onFavoriteTrack,
                    onArtistClick = onArtistClick,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        if (data.relatedAlbums.isNotEmpty()) {
            item(key = "related-albums") {
                MusicSection(title = "Related albums", items = data.relatedAlbums, session = session, onClick = onAlbumClick)
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
    val year = album.releaseDate?.take(4)?.takeIf { it.length == 4 } ?: album.productionYear?.toString()
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.08f).background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.primary.copy(alpha = .34f), MaterialTheme.colorScheme.background)
                )
            ),
            contentAlignment = Alignment.Center,
        ) {
            MusicArtwork(album, session, Modifier.fillMaxWidth(.72f).aspectRatio(1f), album.name)
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(album.albumType ?: "Album", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            Text(album.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
            MusicCreditLine(album, data.artist, onArtistClick)
            Text(
                listOfNotNull(year, album.label, "${data.tracks.distinctBy { it.id }.size} tracks", formatDurationSeconds(data.tracks.sumOf { it.durationSeconds ?: 0.0 })).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (album.genres.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    album.genres.take(5).forEach { genre -> FilterChip(selected = false, onClick = {}, label = { Text(genre) }) }
                }
            }
            album.overview?.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onPlay) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_play), contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Play")
                }
                OutlinedButton(onClick = onShuffle) { Text("Shuffle") }
                IconButton(onClick = onFavorite) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_heart),
                        contentDescription = if (album.favorite) "Remove favorite" else "Add favorite",
                        tint = if (album.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onAddToQueue) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_list_filter), contentDescription = "Add album to queue")
                }
            }
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
        modifier = Modifier.padding(outerPadding),
        topBar = {
            TopAppBar(
                title = { Text(state.data?.artist?.name ?: "Artist", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(LucideR.drawable.lucide_ic_arrow_left), contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading && state.data == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error && state.data == null -> MusicErrorState(Modifier.fillMaxSize().padding(padding), "Could not load this artist", vm::load)
            state.data != null -> ArtistContent(
                data = state.data!!,
                session = session,
                padding = padding,
                tracksLoading = state.tracksLoading,
                tracksError = state.tracksError,
                onFollow = vm::toggleFollowing,
                onFavorite = vm::toggleFavorite,
                onPlayAll = { vm.playAll { tracks -> onPlayTracks(tracks, 0, false) } },
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
    onArtistClick: (String) -> Unit,
    onAlbumClick: (String) -> Unit,
    onTrackClick: (List<MediaItem>, Int) -> Unit,
    onAddToQueue: (List<MediaItem>) -> Unit,
    currentTrackId: String?,
    onScrollabilityChanged: (Boolean) -> Unit,
) {
    val artist = data.artist
    val tracks = data.tracks.distinctBy { it.id }
    val listState = rememberLazyListState()
    ObserveScrollability(
        canScroll = { listState.canScrollForward || listState.canScrollBackward },
        onScrollabilityChanged = onScrollabilityChanged,
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(bottom = 28.dp),
    ) {
        item(key = "artist-header") {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth().height(290.dp),
                    contentAlignment = Alignment.BottomStart,
                ) {
                    MusicArtwork(artist, session, Modifier.fillMaxSize(), artist.name)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f)))))
                    Text(artist.name, style = MaterialTheme.typography.displaySmall, color = Color.White, modifier = Modifier.padding(20.dp).semantics { heading() })
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onPlayAll, enabled = !tracksLoading) {
                            if (tracksLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(painterResource(LucideR.drawable.lucide_ic_play), contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Play all")
                        }
                        OutlinedButton(onClick = onFollow) { Text(if (artist.following == true) "Following" else "Follow") }
                        IconButton(onClick = onFavorite) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_heart),
                                contentDescription = if (artist.favorite) "Remove favorite" else "Add favorite",
                                tint = if (artist.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        "${data.albums.distinctBy { it.id }.size} releases · ${data.trackCount} tracks",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (artist.genres.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        artist.genres.take(6).forEach { FilterChip(selected = false, onClick = {}, label = { Text(it) }) }
                    }
                    artist.overview?.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (tracksError) Text("Could not load tracks", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        val releaseGroups = data.albums.distinctBy { it.id }.groupBy { releaseLabel(it) }
        releaseGroups.forEach { (label, albums) ->
            item(key = "artist-release-$label") {
                MusicSection(label, albums, session, onAlbumClick)
            }
        }
        if (data.appearsIn.isNotEmpty()) {
            item(key = "artist-appears-in") { MusicSection("Appears in", data.appearsIn, session, onAlbumClick) }
        }
        if (data.relatedArtists.isNotEmpty()) {
            item(key = "artist-related") { MusicSection("Related artists", data.relatedArtists, session, onArtistClick) }
        }
        if (tracks.isNotEmpty()) {
            item(key = "artist-tracks-header") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tracks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onAddToQueue(tracks) }) { Text("Add queue") }
                }
            }
            itemsIndexed(tracks, key = { _, track -> "artist-track-${track.id}" }) { index, track ->
                AudioTrackRow(
                    track,
                    session,
                    position = track.trackNumber ?: 0,
                    isCurrent = track.id == currentTrackId,
                    onClick = { onTrackClick(tracks, index) },
                    onArtistClick = onArtistClick,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
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
    var index = 1 // Album header.
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
) {
    val uniqueItems = items.distinctBy { it.id }
    if (uniqueItems.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(uniqueItems, key = { it.id }) { item -> AudioCard(item, session, { onClick(item.id) }) }
        }
    }
}

@Composable
private fun MusicErrorState(modifier: Modifier, message: String, onRetry: () -> Unit) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

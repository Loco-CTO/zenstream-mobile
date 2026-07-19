package com.zenstream.zenstreammobile.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.JellyfinApi
import com.zenstream.zenstreammobile.data.JellyfinRepository
import com.zenstream.zenstreammobile.data.imageUrl
import com.zenstream.zenstreammobile.data.landscapeImageType
import com.zenstream.zenstreammobile.data.posterImageType
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.DetailData
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaPerson
import com.zenstream.zenstreammobile.ui.DetailViewModel
import com.zenstream.zenstreammobile.ui.components.MediaCard
import com.zenstream.zenstreammobile.ui.components.progressPercent
import com.zenstream.zenstreammobile.ui.detailPlaybackTarget

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    repository: JellyfinRepository,
    session: AuthSession,
    itemId: String,
    outerPadding: PaddingValues,
    onBack: () -> Unit,
    onOpenItem: (MediaItem) -> Unit,
    onPlay: (MediaItem) -> Unit,
) {
    val vm: DetailViewModel = viewModel(
        key = "detail-${session.userId}-$itemId",
        factory = DetailViewModel.Factory(repository, session, itemId),
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val title = state.data?.item?.name ?: stringResource(R.string.detail_title)

    Scaffold(
        modifier = Modifier.padding(outerPadding),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        when {
            state.loading && state.data == null -> CenterLoading(innerPadding)
            state.error && state.data == null -> ErrorState(
                innerPadding,
                R.string.detail_load_failed,
                vm::load,
            )

            state.data != null -> DetailContent(
                data = state.data!!,
                session = session,
                padding = innerPadding,
                actionBusy = state.actionBusy,
                actionError = state.actionError,
                onPlay = onPlay,
                onOpenItem = onOpenItem,
                onSelectSeason = vm::selectSeason,
                onTogglePlayed = vm::togglePlayed,
                onToggleFavorite = vm::toggleFavorite,
            )
        }
    }
}

@Composable
internal fun DetailContent(
    data: DetailData,
    session: AuthSession,
    padding: PaddingValues,
    actionBusy: Boolean,
    actionError: Boolean,
    onPlay: (MediaItem) -> Unit,
    onOpenItem: (MediaItem) -> Unit,
    onSelectSeason: (String) -> Unit,
    onTogglePlayed: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val mediaItem = data.item
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            DetailHero(mediaItem, data.parentSeries, session)
        }
        item {
            DetailActions(
                item = mediaItem,
                busy = actionBusy,
                onPlay = { onPlay(playTarget(data)) },
                onTogglePlayed = onTogglePlayed,
                onToggleFavorite = onToggleFavorite,
            )
            if (actionError) {
                Text(
                    stringResource(R.string.detail_action_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        if (mediaItem.genres.isNotEmpty()) {
            item { GenreRow(mediaItem.genres) }
        }
        mediaItem.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            item {
                ExpandableOverview(overview)
            }
        }
        if (mediaItem.type == "Episode" && data.parentSeries != null) {
            item {
                OutlinedButton(
                    onClick = { onOpenItem(data.parentSeries) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.parent_series, data.parentSeries.name))
                }
            }
        }
        if (mediaItem.type == "Series" || mediaItem.type == "Episode") {
            item {
                EpisodeSection(
                    data = data,
                    session = session,
                    onSelectSeason = onSelectSeason,
                    onOpenItem = onOpenItem,
                )
            }
        }
        if (mediaItem.people.isNotEmpty()) {
            item { PeopleSection(mediaItem.people, session) }
        }
        if (data.similar.isNotEmpty()) {
            item {
                SectionTitle(R.string.more_like_this)
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(data.similar, key = { it.id }) { similar ->
                        MediaCard(similar, session, wide = false, onClick = onOpenItem)
                    }
                }
            }
        }
    }
}

private fun playTarget(data: DetailData): MediaItem = detailPlaybackTarget(data.item, data.episodes)

@Composable
private fun ExpandableOverview(overview: String) {
    var expanded by remember(overview) { mutableStateOf(false) }
    var canExpand by remember(overview) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            overview,
            maxLines = if (expanded) Int.MAX_VALUE else OVERVIEW_COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded) canExpand = result.hasVisualOverflow
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canExpand || expanded) {
            androidx.compose.material3.TextButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                )
                Spacer(Modifier.width(4.dp))
                Text(stringResource(if (expanded) R.string.show_less else R.string.show_more))
            }
        }
    }
}

private const val OVERVIEW_COLLAPSED_LINES = 4

@Composable
private fun DetailHero(item: MediaItem, parentSeries: MediaItem?, session: AuthSession) {
    val backdropItem = if (item.backdropImageTags.isNotEmpty()) item else parentSeries
    val backdrop = backdropItem?.let {
        imageUrl(session.serverUrl, it, "Backdrop", 1280, 720)
    }
    val artworkType =
        if (item.type == "Episode") landscapeImageType(item) else posterImageType(item)
    val artwork = artworkType?.let { imageUrl(session.serverUrl, item, it, 480, 720) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(290.dp)
            .background(Color.Black),
    ) {
        AuthenticatedImage(
            url = backdrop,
            session = session,
            description = null,
            modifier = Modifier
                .fillMaxSize()
                .alpha(.52f),
            scale = ContentScale.Crop,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color(0xFF080808))),
                ),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            AuthenticatedImage(
                url = artwork,
                session = session,
                description = stringResource(R.string.poster_description, item.name),
                modifier = Modifier
                    .size(if (item.type == "Episode") 170.dp else 104.dp, 156.dp)
                    .clip(RoundedCornerShape(8.dp)),
                scale = ContentScale.Crop,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (item.type == "Episode") {
                    Text(
                        stringResource(
                            R.string.season_episode,
                            item.parentIndexNumber ?: 0,
                            item.indexNumber ?: 0,
                        ),
                        color = Color.White.copy(alpha = .7f),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    item.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                Metadata(item)
            }
        }
    }
}

@Composable
private fun Metadata(item: MediaItem) {
    val parts = buildList {
        item.communityRating?.let { add(stringResource(R.string.rating_value, it)) }
        item.productionYear?.let { add(stringResource(R.string.year, it)) }
        item.officialRating?.let { add(it) }
        item.runtimeTicks?.let {
            val minutes = (it / 10_000_000L / 60L).toInt()
            if (minutes > 0) add(stringResource(R.string.runtime_minutes, minutes))
        }
        item.studios.firstOrNull()?.let(::add)
    }
    if (parts.isNotEmpty()) {
        Text(
            parts.joinToString("  •  "),
            color = Color.White.copy(alpha = .65f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailActions(
    item: MediaItem,
    busy: Boolean,
    onPlay: () -> Unit,
    onTogglePlayed: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val watchedLabel =
        stringResource(if (item.played) R.string.mark_unwatched else R.string.mark_watched)
    val favoriteLabel =
        stringResource(if (item.favorite) R.string.remove_favorite else R.string.add_favorite)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onPlay, enabled = !busy) {
            Icon(Icons.Default.PlayArrow, stringResource(R.string.play_description, item.name))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.play))
        }
        IconButton(
            onClick = onTogglePlayed,
            enabled = !busy,
            modifier = Modifier.semantics {
                contentDescription = watchedLabel
            },
        ) {
            Icon(
                Icons.Default.Check,
                null,
                tint = if (item.played) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(
            onClick = onToggleFavorite,
            enabled = !busy,
            modifier = Modifier.semantics {
                contentDescription = favoriteLabel
            },
        ) {
            Icon(
                if (item.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                null,
                tint = if (item.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GenreRow(genres: List<String>) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        genres.forEach { genre ->
            FilterChip(selected = false, onClick = {}, label = { Text(genre) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EpisodeSection(
    data: DetailData,
    session: AuthSession,
    onSelectSeason: (String) -> Unit,
    onOpenItem: (MediaItem) -> Unit,
) {
    SectionTitle(R.string.episodes_label)
    if (data.seasons.size > 1) {
        var expanded by remember { mutableStateOf(false) }
        val selected = data.seasons.firstOrNull { it.id == data.selectedSeasonId }
        Box(Modifier.padding(horizontal = 16.dp)) {
            OutlinedButton(onClick = { expanded = true }) {
                Text(seasonLabel(selected))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                data.seasons.forEach { season ->
                    DropdownMenuItem(
                        text = { Text(seasonLabel(season)) },
                        onClick = {
                            expanded = false
                            onSelectSeason(season.id)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (data.episodes.isEmpty()) {
        Text(
            stringResource(R.string.no_episodes),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    } else {
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            data.episodes.forEach { episode ->
                EpisodeRow(episode, session, onClick = { onOpenItem(episode) })
            }
        }
    }
}

@Composable
private fun seasonLabel(season: MediaItem?): String = season?.let {
    it.indexNumber?.let { number -> stringResource(R.string.season_value, number, it.name) }
        ?: it.name
} ?: stringResource(R.string.no_season)

@Composable
private fun EpisodeRow(item: MediaItem, session: AuthSession, onClick: () -> Unit) {
    val type = landscapeImageType(item)
    val url = type?.let { imageUrl(session.serverUrl, item, it, 320, 180) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier
                .width(128.dp)
                .height(72.dp)
                .clip(RoundedCornerShape(6.dp))) {
                AuthenticatedImage(
                    url,
                    session,
                    stringResource(R.string.episode_description, item.name),
                    Modifier.fillMaxSize(),
                    ContentScale.Crop,
                )
                progressPercent(item)?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.episode_title, item.indexNumber ?: 0, item.name),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                item.overview?.let {
                    Text(
                        it,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (item.played) Icon(
                Icons.Default.Check,
                stringResource(R.string.watched_description),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun PeopleSection(people: List<MediaPerson>, session: AuthSession) {
    SectionTitle(R.string.cast_crew)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(
            people.filter { it.type == "Actor" || it.type == "Director" }.take(20),
            key = { "${it.name}-${it.role}" }) { person ->
            val url = person.primaryImageTag?.let {
                "${session.serverUrl.trimEnd('/')}/Persons/${Uri.encode(person.name)}/Images/Primary?maxWidth=144&quality=90&tag=${
                    Uri.encode(
                        it
                    )
                }"
            }
            Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AuthenticatedImage(
                    url,
                    session,
                    stringResource(R.string.person_description, person.name),
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape),
                    ContentScale.Crop,
                )
                Text(
                    person.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    person.role ?: person.type.orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(@androidx.annotation.StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .78f),
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .semantics { heading() },
    )
}

@Composable
private fun AuthenticatedImage(
    url: String?,
    session: AuthSession,
    description: String?,
    modifier: Modifier,
    scale: ContentScale,
) {
    val request = url?.let {
        ImageRequest.Builder(LocalContext.current).data(it).httpHeaders(
            NetworkHeaders.Builder()
                .set("Authorization", JellyfinApi.authorizationHeader(session.token)).build(),
        ).crossfade(true).build()
    }
    AsyncImage(
        model = request,
        contentDescription = description,
        contentScale = scale,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

@Composable
private fun CenterLoading(padding: PaddingValues) {
    Box(Modifier
        .fillMaxSize()
        .padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ErrorState(padding: PaddingValues, message: Int, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(message),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Default.Refresh, stringResource(R.string.retry))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.retry))
        }
    }
}

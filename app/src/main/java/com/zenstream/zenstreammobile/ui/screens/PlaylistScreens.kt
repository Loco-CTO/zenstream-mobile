package com.zenstream.zenstreammobile.ui.screens

import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.FavoritesDataSource
import com.zenstream.zenstreammobile.data.MusicDataSource
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.PlaylistData
import com.zenstream.zenstreammobile.model.PlaylistEntry
import com.zenstream.zenstreammobile.model.PlaylistSummary
import com.zenstream.zenstreammobile.ui.components.MediaImage
import com.zenstream.zenstreammobile.ui.components.MusicArtwork
import com.zenstream.zenstreammobile.ui.components.progressPercent
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlaylistPickerButton(
    repository: MusicDataSource,
    session: AuthSession,
    source: MediaItem,
    trackItems: List<MediaItem> = emptyList(),
    enabled: Boolean = true,
    triggerContent: (@Composable (onClick: () -> Unit) -> Unit)? = null,
    openRequest: Int = 0,
    showTrigger: Boolean = true,
) {
    var open by remember(source.id) { mutableStateOf(false) }
    val tracks = remember(source.id, trackItems) { trackItems.distinctBy { it.id } }
    val addDescription = stringResource(R.string.add_to_playlist)
    LaunchedEffect(openRequest) {
        if (openRequest > 0) open = true
    }
    val openPicker = { if (enabled) open = true }
    if (showTrigger) {
        if (triggerContent == null) {
            IconButton(
                enabled = enabled,
                onClick = openPicker,
                modifier = Modifier.semantics { contentDescription = "${source.name}: $addDescription" },
            ) {
                Icon(
                    painter = painterResource(LucideR.drawable.lucide_ic_circle_plus),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            Box(Modifier.semantics { contentDescription = "${source.name}: $addDescription" }) {
                triggerContent(openPicker)
            }
        }
    }
    if (open) {
        PlaylistPickerDialog(
            repository = repository,
            session = session,
            source = source,
            providedTracks = tracks,
            onDismiss = { open = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistPickerDialog(
    repository: MusicDataSource,
    session: AuthSession,
    source: MediaItem,
    providedTracks: List<MediaItem>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var summaries by remember { mutableStateOf<List<PlaylistSummary>>(emptyList()) }
    var details by remember { mutableStateOf<Map<String, PlaylistData>>(emptyMap()) }
    var pendingMembership by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var selectedTracks by remember { mutableStateOf(providedTracks) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var createDialog by remember { mutableStateOf(false) }

    suspend fun refresh() {
        loading = true
        try {
            summaries = repository.playlists(session)
            details = summaries.associate { summary ->
                summary.id to repository.playlist(session, summary.id)
            }
            error = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            error = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(source.id) {
        selectedTracks =
            when (source.type) {
                "Audio" -> listOf(source)
                "MusicAlbum" -> providedTracks
                "MusicArtist" ->
                    try {
                        repository.musicArtistTracks(session, source.id)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        providedTracks
                    }
                else -> emptyList()
            }.distinctBy { it.id }
        refresh()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = Color.Black.copy(alpha = 0.58f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.36f))
        },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.add_to_playlist),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_x),
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { createDialog = true },
                    enabled = !busy && selectedTracks.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_plus), contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.create_playlist))
                }
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    error -> Text(stringResource(R.string.playlists_load_failed), color = MaterialTheme.colorScheme.error)
                    selectedTracks.isEmpty() -> Text(stringResource(R.string.playlist_no_audio_tracks))
                    summaries.isEmpty() -> Text(stringResource(R.string.playlists_empty))
                    else ->
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(summaries, key = { it.id }) { summary ->
                                val detail = details[summary.id]
                                val trackIds = selectedTracks.mapTo(hashSetOf()) { it.id }
                                val memberIds = detail?.items?.mapTo(hashSetOf()) { it.item.id }.orEmpty()
                                val savedSelection = trackIds.isNotEmpty() && memberIds.containsAll(trackIds)
                                val selected = pendingMembership[summary.id] ?: savedSelection
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .toggleable(
                                            value = selected,
                                            enabled = !busy && detail != null,
                                            role = Role.Checkbox,
                                        ) { shouldBeMember ->
                                            val current = detail ?: return@toggleable
                                            pendingMembership = pendingMembership + (summary.id to shouldBeMember)
                                            busy = true
                                            error = false
                                            scope.launch {
                                                fun publish(updated: PlaylistData) {
                                                    details = details + (summary.id to updated)
                                                    summaries = summaries.map {
                                                        if (it.id == summary.id) updated.summary else it
                                                    }
                                                }

                                                fun containsSelection(playlist: PlaylistData): Boolean {
                                                    val ids = playlist.items.mapTo(hashSetOf()) { it.item.id }
                                                    return trackIds.isNotEmpty() && ids.containsAll(trackIds)
                                                }

                                                try {
                                                    if (shouldBeMember) {
                                                        repository.addPlaylistItems(
                                                            session,
                                                            summary.id,
                                                            listOf(source.id),
                                                        )
                                                    } else {
                                                        current.items
                                                            .filter { it.item.id in trackIds }
                                                            .forEach { entry ->
                                                                repository.removePlaylistEntry(
                                                                    session,
                                                                    summary.id,
                                                                    entry.entryId,
                                                                )
                                                            }
                                                    }
                                                    val updated = repository.playlist(session, summary.id)
                                                    publish(updated)
                                                    error = containsSelection(updated) != shouldBeMember
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (_: Throwable) {
                                                    try {
                                                        val updated = repository.playlist(session, summary.id)
                                                        publish(updated)
                                                        error = containsSelection(updated) != shouldBeMember
                                                    } catch (cancelled: CancellationException) {
                                                        throw cancelled
                                                    } catch (_: Throwable) {
                                                        error = true
                                                    }
                                                } finally {
                                                    pendingMembership = pendingMembership - summary.id
                                                    busy = false
                                                }
                                            }
                                        }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    PlaylistArtwork(summary.artworkItems, session, Modifier.size(42.dp))
                                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        Text(summary.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            stringResource(R.string.playlist_track_count, summary.itemCount),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    PlaylistMembershipCheckbox(selected)
                                }
                            }
                        }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
            }
        }
    }

    if (createDialog) {
        PlaylistEditorDialog(
            title = stringResource(R.string.new_playlist),
            onDismiss = { createDialog = false },
            onSave = { name, description, isPrivate ->
                scope.launch {
                    busy = true
                    try {
                        repository.createPlaylist(
                            session,
                            name,
                            description,
                            isPrivate,
                            source.id,
                        )
                        createDialog = false
                        refresh()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        error = true
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }
}

@Composable
private fun PlaylistMembershipCheckbox(checked: Boolean) {
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = 190),
        label = "playlist membership checkbox",
    )
    val outline = MaterialTheme.colorScheme.outline
    val foreground = MaterialTheme.colorScheme.onSurface
    val border = lerp(outline, foreground.copy(alpha = 0.82f), progress)
    val fill = lerp(Color.Transparent, foreground, progress)

    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.6.dp.toPx()
        val corner = 3.dp.toPx()
        drawRoundRect(
            color = fill,
            cornerRadius = CornerRadius(corner),
        )
        drawRoundRect(
            color = border,
            cornerRadius = CornerRadius(corner),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
    }
}

@Composable
fun PlaylistEditorDialog(
    title: String,
    initialName: String = "",
    initialDescription: String = "",
    initialPrivate: Boolean = true,
    onDismiss: () -> Unit,
    onSave: (String, String?, Boolean) -> Unit,
) {
    var name by remember(title, initialName) { mutableStateOf(initialName) }
    var description by remember(title, initialDescription) { mutableStateOf(initialDescription) }
    var isPrivate by remember(title, initialPrivate) { mutableStateOf(initialPrivate) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text(stringResource(R.string.playlist_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(500) },
                    label = { Text(stringResource(R.string.playlist_description_optional)) },
                    minLines = 2,
                    maxLines = 3,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.playlist_private))
                        Text(
                            stringResource(R.string.playlist_private_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), description.trim().takeIf(String::isNotEmpty), isPrivate) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun WatchlistContent(
    repository: FavoritesDataSource,
    session: AuthSession,
    onItemClick: (MediaItem) -> Unit,
    onScrollabilityChanged: (Boolean) -> Unit,
) {
    var items by remember(session.userId, session.token) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember(session.userId, session.token) { mutableStateOf(true) }
    var error by remember(session.userId, session.token) { mutableStateOf(false) }
    var refresh by remember(session.userId, session.token) { mutableIntStateOf(0) }
    var busyItemId by remember(session.userId, session.token) { mutableStateOf<String?>(null) }
    var actionError by remember(session.userId, session.token) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    ObserveScrollability(
        canScroll = { listState.canScrollForward || listState.canScrollBackward },
        onScrollabilityChanged = onScrollabilityChanged,
    )
    LaunchedEffect(session.userId, session.token, refresh) {
        loading = true
        try {
            items = repository.watchlist(session)
            error = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            error = true
        } finally {
            loading = false
        }
    }
    when {
        loading && items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        error && items.isEmpty() -> TextButton(onClick = { refresh++ }) { Text(stringResource(R.string.watchlist_load_failed)) }
        items.isEmpty() -> Text(stringResource(R.string.watchlist_empty), modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else ->
            Column(Modifier.fillMaxSize()) {
                if (actionError) {
                    Text(
                        stringResource(R.string.watchlist_load_failed),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        WatchlistRow(
                            item = item,
                            session = session,
                            busy = busyItemId != null,
                            onClick = { onItemClick(item) },
                            onFavorite = {
                                if (busyItemId == null) scope.launch {
                                    busyItemId = item.id
                                    try {
                                        repository.setFavorite(session, item.id, !item.favorite)
                                        actionError = false
                                        refresh++
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Throwable) {
                                        actionError = true
                                    } finally {
                                        busyItemId = null
                                    }
                                }
                            },
                            onRemove = {
                                if (busyItemId == null) scope.launch {
                                    busyItemId = item.id
                                    try {
                                        repository.setFollowing(session, item.id, false)
                                        actionError = false
                                        items = items.filterNot { it.id == item.id }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (_: Throwable) {
                                        actionError = true
                                    } finally {
                                        busyItemId = null
                                    }
                                }
                            },
                        )
                    }
                }
            }
    }
}

@Composable
private fun WatchlistRow(
    item: MediaItem,
    session: AuthSession,
    busy: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onRemove: () -> Unit,
) {
    val status = item.watchlistStatus
    val statusText =
        when (status?.kind) {
            "continue" -> stringResource(R.string.watchlist_continue)
            "upNext" -> stringResource(R.string.watchlist_up_next)
            else -> null
        }
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaImage(item, session, wide = false, modifier = Modifier.size(width = 72.dp, height = 96.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            statusText?.let {
                Text(
                    buildString {
                        append(it)
                        if (status?.seasonNumber != null && status.episodeNumber != null) {
                            append(" · S${status.seasonNumber}:E${status.episodeNumber}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            progressPercent(item)?.let { percent ->
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
        IconButton(onClick = onFavorite, enabled = !busy) {
            Icon(
                painterResource(LucideR.drawable.lucide_ic_heart),
                contentDescription = stringResource(if (item.favorite) R.string.remove_favorite else R.string.add_favorite),
                tint = if (item.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove, enabled = !busy) {
            Icon(painterResource(LucideR.drawable.lucide_ic_trash_2), contentDescription = stringResource(R.string.remove_from_watchlist))
        }
    }
}

@Composable
fun PlaylistLibraryContent(
    repository: FavoritesDataSource,
    session: AuthSession,
    onPlayTracks: (List<MediaItem>, Int, Boolean?, Boolean) -> Unit,
    onScrollabilityChanged: (Boolean) -> Unit,
) {
    var summaries by remember(session.userId, session.token) { mutableStateOf<List<PlaylistSummary>>(emptyList()) }
    var selectedId by remember(session.userId, session.token) { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<PlaylistData?>(null) }
    var loading by remember(session.userId, session.token) { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var editor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(session.userId, session.token, selectedId, revision) {
        loading = true
        try {
            summaries = repository.playlists(session)
            detail = selectedId?.let { repository.playlist(session, it) }
            error = false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            error = true
        } finally {
            loading = false
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    ObserveScrollability(
        canScroll = { listState.canScrollForward || listState.canScrollBackward },
        onScrollabilityChanged = onScrollabilityChanged,
    )

    if (selectedId == null) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.playlists), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(onClick = { editor = true }) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_plus), contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.create_playlist))
                }
            }
            when {
                loading && summaries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error && summaries.isEmpty() -> TextButton(onClick = { revision++ }) { Text(stringResource(R.string.playlists_load_failed)) }
                summaries.isEmpty() -> Text(stringResource(R.string.playlists_empty), modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else ->
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 20.dp)) {
                        items(summaries, key = { it.id }) { summary ->
                            PlaylistCard(summary, session, onClick = { selectedId = summary.id })
                        }
                    }
            }
        }
    } else {
        PlaylistDetailContent(
            data = detail,
            loading = loading,
            error = error,
            session = session,
            onBack = { selectedId = null },
            onPlayTracks = onPlayTracks,
            onEdit = { editing = true },
            onDelete = {
                scope.launch {
                    try {
                        repository.deletePlaylist(session, selectedId!!)
                        selectedId = null
                        detail = null
                        revision++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        error = true
                    }
                }
            },
            onReorder = reorder@{ entryIds ->
                val playlistId = selectedId ?: return@reorder
                val previous = detail ?: return@reorder
                val byId = previous.items.associateBy { it.entryId }
                val reordered = entryIds.mapNotNull(byId::get)
                if (reordered.size != previous.items.size) return@reorder
                detail = previous.copy(items = reordered)
                scope.launch {
                    try {
                        detail = repository.reorderPlaylist(session, playlistId, entryIds)
                        revision++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        detail = previous
                        error = true
                    }
                }
            },
            onRemoveEntry = { entryId ->
                scope.launch {
                    try {
                        repository.removePlaylistEntry(session, selectedId!!, entryId)
                        revision++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        error = true
                    }
                }
            },
            onRefresh = { revision++ },
        )
    }

    if (editor) {
        PlaylistEditorDialog(
            title = stringResource(R.string.new_playlist),
            onDismiss = { editor = false },
            onSave = { name, description, isPrivate ->
                scope.launch {
                    try {
                        val created = repository.createPlaylist(session, name, description, isPrivate)
                        summaries = repository.playlists(session)
                        editor = false
                        selectedId = created.summary.id
                        revision++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        error = true
                    }
                }
            },
        )
    }
    val current = detail
    if (editing && current != null) {
        PlaylistEditorDialog(
            title = stringResource(R.string.edit_playlist),
            initialName = current.summary.name,
            initialDescription = current.summary.description.orEmpty(),
            initialPrivate = current.summary.isPrivate,
            onDismiss = { editing = false },
            onSave = { name, description, isPrivate ->
                scope.launch {
                    try {
                        repository.updatePlaylist(session, current.summary.id, name, description, isPrivate)
                        editing = false
                        revision++
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        error = true
                    }
                }
            },
        )
    }
}

@Composable
private fun PlaylistCard(summary: PlaylistSummary, session: AuthSession, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistArtwork(summary.artworkItems, session, Modifier.size(88.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(summary.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    stringResource(R.string.playlist_track_count, summary.itemCount),
                    if (summary.isPrivate) stringResource(R.string.playlist_private) else stringResource(R.string.playlist_public),
                    summary.description?.takeIf(String::isNotBlank),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(painterResource(LucideR.drawable.lucide_ic_chevron_right), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlaylistArtwork(items: List<MediaItem>, session: AuthSession, modifier: Modifier = Modifier) {
    val visible = items.take(4)
    Surface(modifier.clip(RoundedCornerShape(10.dp)), color = MaterialTheme.colorScheme.surfaceVariant) {
        when (visible.size) {
            0 -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(painterResource(LucideR.drawable.lucide_ic_list_music), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            1 -> MusicArtwork(visible.first(), session, modifier = Modifier.fillMaxSize(), requestedSize = 320)
            else -> Column(Modifier.fillMaxSize()) {
                Row(Modifier.weight(1f)) {
                    visible.take(2).forEach { item -> MusicArtwork(item, session, modifier = Modifier.weight(1f).fillMaxSize(), requestedSize = 160) }
                }
                if (visible.size > 2) Row(Modifier.weight(1f)) {
                    visible.drop(2).forEach { item -> MusicArtwork(item, session, modifier = Modifier.weight(1f).fillMaxSize(), requestedSize = 160) }
                    if (visible.size == 3) Spacer(Modifier.weight(1f).fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetailContent(
    data: PlaylistData?,
    loading: Boolean,
    error: Boolean,
    session: AuthSession,
    onBack: () -> Unit,
    onPlayTracks: (List<MediaItem>, Int, Boolean?, Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onReorder: (List<String>) -> Unit,
    onRemoveEntry: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val currentSummary = data?.summary
    val edgeScrollThresholdPx = with(LocalDensity.current) { 72.dp.toPx() }
    val fallbackRowHeightPx = with(LocalDensity.current) { 72.dp.toPx() }
    val minimumScrollPx = with(LocalDensity.current) { 4.dp.toPx() }
    val extraScrollPx = with(LocalDensity.current) { 20.dp.toPx() }
    val rowHeights = remember { mutableMapOf<String, Float>() }
    var viewportHeightPx by remember { mutableStateOf(0) }
    var draggedEntryId by remember { mutableStateOf<String?>(null) }
    var draggedFrom by remember { mutableStateOf(-1) }
    var insertionIndex by remember { mutableStateOf(-1) }
    var draggedCenterY by remember { mutableStateOf(0f) }
    var draggedHeightPx by remember { mutableStateOf(0f) }

    fun updateInsertionIndex(entries: List<PlaylistEntry>) {
        val nearest = listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val id = info.key as? String ?: return@mapNotNull null
            val index = entries.indexOfFirst { it.entryId == id }
            if (index < 0) null else index to abs(info.offset + info.size / 2f - draggedCenterY)
        }.minByOrNull { it.second }
        if (nearest != null) insertionIndex = nearest.first
    }

    fun clearDrag() {
        draggedEntryId = null
        draggedFrom = -1
        insertionIndex = -1
        draggedCenterY = 0f
        draggedHeightPx = 0f
    }

    LaunchedEffect(draggedEntryId) {
        while (draggedEntryId != null) {
            val edgeDistance = when {
                draggedCenterY > viewportHeightPx - edgeScrollThresholdPx && listState.canScrollForward ->
                    draggedCenterY - (viewportHeightPx - edgeScrollThresholdPx)
                draggedCenterY < edgeScrollThresholdPx && listState.canScrollBackward ->
                    draggedCenterY - edgeScrollThresholdPx
                else -> 0f
            }
            if (edgeDistance != 0f && viewportHeightPx > 0) {
                val speed = minimumScrollPx + extraScrollPx *
                    (abs(edgeDistance) / edgeScrollThresholdPx).coerceIn(0f, 1f)
                listState.scrollBy(if (edgeDistance > 0f) speed else -speed)
                data?.items?.let(::updateInsertionIndex)
            }
            delay(16)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(painterResource(LucideR.drawable.lucide_ic_arrow_left), contentDescription = stringResource(R.string.back)) }
            Text(currentSummary?.name ?: stringResource(R.string.playlist), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (currentSummary?.isOwner == true) {
                IconButton(onClick = onEdit) { Icon(painterResource(LucideR.drawable.lucide_ic_pencil), contentDescription = stringResource(R.string.edit_playlist)) }
                IconButton(onClick = onDelete) { Icon(painterResource(LucideR.drawable.lucide_ic_trash_2), contentDescription = stringResource(R.string.delete_playlist)) }
            }
        }
        when {
            loading && data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            error && data == null -> TextButton(onClick = onRefresh) { Text(stringResource(R.string.playlists_load_failed)) }
            data != null -> {
                val summary = data.summary
                val entries = data.items
                val tracks = entries.map { it.item }
                Box(Modifier.fillMaxSize().onSizeChanged { viewportHeightPx = it.height }) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().pointerInput(entries.map { it.entryId }, summary.isOwner) {
                            if (summary.isOwner) detectDragGesturesAfterLongPress(
                                onDragStart = { touch ->
                                    val info = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                        touch.y >= it.offset && touch.y < it.offset + it.size &&
                                            entries.any { entry -> entry.entryId == it.key }
                                    } ?: return@detectDragGesturesAfterLongPress
                                    val id = info.key as String
                                    draggedEntryId = id
                                    draggedFrom = entries.indexOfFirst { it.entryId == id }
                                    insertionIndex = draggedFrom
                                    draggedCenterY = info.offset + info.size / 2f
                                    draggedHeightPx = info.size.toFloat()
                                },
                                onDragCancel = ::clearDrag,
                                onDragEnd = {
                                    val from = draggedFrom
                                    val target = insertionIndex
                                    clearDrag()
                                    if (from in entries.indices && target in entries.indices && from != target) {
                                        val reordered = entries.toMutableList()
                                        val moving = reordered.removeAt(from)
                                        reordered.add(target, moving)
                                        onReorder(reordered.map { it.entryId })
                                    }
                                },
                                onDrag = { change, dragAmount ->
                                    if (draggedEntryId != null) {
                                        change.consume()
                                        draggedCenterY += dragAmount.y
                                        updateInsertionIndex(entries)
                                    }
                                },
                            )
                        },
                        contentPadding = PaddingValues(bottom = 24.dp),
                    ) {
                    item(key = "playlist-summary") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PlaylistArtwork(summary.artworkItems, session, Modifier.size(112.dp))
                                Column(Modifier.padding(start = 16.dp)) {
                                    Text(stringResource(R.string.playlist_track_count, summary.itemCount), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(if (summary.isPrivate) stringResource(R.string.playlist_private) else stringResource(R.string.playlist_public), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            summary.description?.takeIf(String::isNotBlank)?.let {
                                Text(it, modifier = Modifier.padding(top = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                                Button(onClick = { if (tracks.isNotEmpty()) onPlayTracks(tracks, 0, null, false) }, enabled = tracks.isNotEmpty()) {
                                    Icon(painterResource(LucideR.drawable.lucide_ic_play), contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.play_all))
                                }
                                OutlinedButton(onClick = { if (tracks.isNotEmpty()) onPlayTracks(tracks, 0, true, false) }, enabled = tracks.isNotEmpty()) {
                                    Icon(painterResource(LucideR.drawable.lucide_ic_shuffle), contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(R.string.shuffle))
                                }
                                if (summary.isOwner && !summary.isPrivate && !summary.shareToken.isNullOrBlank()) {
                                    IconButton(onClick = {
                                        val url = "${session.serverUrl.trimEnd('/')}/shared/playlist/${summary.shareToken}"
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url),
                                                context.getString(R.string.share_playlist),
                                            )
                                        )
                                    }) {
                                        Icon(painterResource(LucideR.drawable.lucide_ic_share_2), contentDescription = stringResource(R.string.share_playlist))
                                    }
                                }
                            }
                        }
                    }
                    if (entries.isEmpty()) item(key = "playlist-empty") {
                        Text(stringResource(R.string.playlist_empty), modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    itemsIndexed(entries, key = { _, entry -> entry.entryId }) { index, entry ->
                        val isDragged = draggedEntryId == entry.entryId
                        val rowStepPx = rowHeights[entry.entryId] ?: fallbackRowHeightPx
                        val targetOffsetPx = when {
                            isDragged -> 0f
                            draggedFrom >= 0 && insertionIndex > draggedFrom && index in (draggedFrom + 1)..insertionIndex -> -rowStepPx
                            draggedFrom >= 0 && insertionIndex in 0 until draggedFrom && index in insertionIndex until draggedFrom -> rowStepPx
                            else -> 0f
                        }
                        val animatedOffsetPx by animateFloatAsState(
                            targetValue = targetOffsetPx,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                            label = "playlist row movement",
                        )
                        val liftProgress by animateFloatAsState(
                            targetValue = if (isDragged) 1f else 0f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
                            label = "playlist drag lift",
                        )
                        Row(
                            Modifier.fillMaxWidth()
                                .offset { IntOffset(0, animatedOffsetPx.roundToInt()) }
                                .graphicsLayer {
                                    val scale = 1f + liftProgress * .02f
                                    scaleX = scale
                                    scaleY = scale
                                    alpha = if (isDragged) .96f else 1f
                                }
                                .onSizeChanged { rowHeights[entry.entryId] = it.height.toFloat() }
                                .graphicsLayer { alpha = if (isDragged) 0f else 1f }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MusicArtwork(entry.item, session, modifier = Modifier.size(52.dp), requestedSize = 160)
                            Column(
                                Modifier.weight(1f).clickable { onPlayTracks(tracks, index, null, true) }.padding(horizontal = 10.dp),
                            ) {
                                Text(entry.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(entry.item.album.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (summary.isOwner) {
                                IconButton(onClick = { onRemoveEntry(entry.entryId) }) {
                                    Icon(painterResource(LucideR.drawable.lucide_ic_x), contentDescription = stringResource(R.string.remove_from_playlist))
                                }
                            }
                        }
                    }
                }
                    val dragged = entries.firstOrNull { it.entryId == draggedEntryId }
                    if (dragged != null) {
                        val previewTop = (draggedCenterY - draggedHeightPx / 2f)
                            .coerceIn(0f, (viewportHeightPx - draggedHeightPx).coerceAtLeast(0f))
                        Surface(
                            modifier = Modifier.fillMaxWidth()
                                .offset { IntOffset(0, previewTop.roundToInt()) }
                                .zIndex(2f),
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 4.dp,
                            shadowElevation = 10.dp,
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MusicArtwork(dragged.item, session, modifier = Modifier.size(52.dp), requestedSize = 160)
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    Text(dragged.item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                    Text(dragged.item.album.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (summary.isOwner) Spacer(Modifier.size(48.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.zenstream.zenstreammobile.offline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun ColumnScope.OfflinePlaylistsScreen(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    onItem: (OfflineProfile, MediaItem) -> Unit,
) {
    val rows by runtime.playlists.playlists(profile.id).collectAsState(initial = emptyList())
    val revision by runtime.catalogRevision.collectAsState()
    val actions = LocalDownloadUi.current
    val scope = rememberCoroutineScope()
    var selectedId by remember(profile.id) { mutableStateOf<String?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var editDialog by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    val selected = rows.firstOrNull { it.id == selectedId }
    if (error)
        Text(
            stringResource(R.string.offline_request_failed),
            color = MaterialTheme.colorScheme.error,
        )

    if (selected == null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.offline_playlists),
                style = MaterialTheme.typography.titleLarge,
            )
            FilledTonalButton(onClick = { createDialog = true }) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_plus),
                    contentDescription = null,
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.offline_playlist_new))
            }
        }
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.offline_playlist_empty),
                modifier = Modifier.padding(vertical = 16.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(rows, key = { it.id }) { row ->
                    val metadata = remember(row.metadata, revision) { JSONObject(row.metadata) }
                    val count by
                        produceState(0, profile.id, row.id, revision) {
                            value =
                                runtime.database.offline().playlistEntryCount(profile.id, row.id)
                        }
                    val pending by
                        produceState(false, profile.id, row.id, revision) {
                            value =
                                runtime.database
                                    .offline()
                                    .hasPendingPlaylistOperations(profile.id, row.id)
                        }
                    val isComplete = row.complete && count == row.expectedCount
                    ListItem(
                        headlineContent = {
                            Text(
                                metadata.optString(
                                    "name",
                                    stringResource(R.string.offline_unknown_item),
                                )
                            )
                        },
                        supportingContent = {
                            Column {
                                Text(
                                    stringResource(
                                        R.string.offline_playlist_entry_count,
                                        count,
                                        row.expectedCount,
                                    )
                                )
                                if (!isComplete)
                                    Text(stringResource(R.string.offline_playlist_incomplete))
                                else if (pending)
                                    Text(stringResource(R.string.offline_action_pending))
                            }
                        },
                        trailingContent = {
                            if (!isComplete)
                                TextButton(
                                    enabled = actions != null,
                                    onClick = {
                                        actions
                                            ?.savePlaylistMetadata
                                            ?.invoke(
                                                DownloadSpec(
                                                    row.id,
                                                    metadata.optString("name", row.id),
                                                    kind = "playlist_metadata",
                                                )
                                            )
                                    },
                                ) {
                                    Text(stringResource(R.string.offline_playlist_save_membership))
                                }
                        },
                        modifier = Modifier.clickable { selectedId = row.id },
                    )
                }
            }
        }
    } else {
        OfflinePlaylistDetail(
            runtime = runtime,
            profile = profile,
            playlist = selected,
            revision = revision,
            onBack = { selectedId = null },
            onEdit = { editDialog = selected.id },
            onDelete = { deleting = selected.id },
            onItem = onItem,
            onError = { error = it },
        )
    }

    if (createDialog)
        PlaylistDetailsDialog(
            title = stringResource(R.string.offline_playlist_new),
            initialName = "",
            initialDescription = "",
            onDismiss = { createDialog = false },
            onSave = { name, description ->
                scope.launch {
                    try {
                        runtime.playlists.create(profile.id, name, description)
                        selectedId = null
                        createDialog = false
                        error = false
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        error = true
                    }
                }
            },
        )

    editDialog?.let { id ->
        val row = rows.firstOrNull { it.id == id }
        if (row != null) {
            val metadata = remember(row.metadata) { JSONObject(row.metadata) }
            PlaylistDetailsDialog(
                title = stringResource(R.string.offline_playlist_edit),
                initialName = metadata.optString("name"),
                initialDescription = metadata.optString("description"),
                onDismiss = { editDialog = null },
                onSave = { name, description ->
                    scope.launch {
                        try {
                            runtime.playlists.updateDetails(profile.id, id, name, description)
                            editDialog = null
                            error = false
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            error = true
                        }
                    }
                },
            )
        }
    }

    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.offline_playlist_delete)) },
            text = { Text(stringResource(R.string.offline_playlist_delete_help)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            try {
                                runtime.playlists.delete(profile.id, id)
                                selectedId = null
                                deleting = null
                                error = false
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure
                                error = true
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.offline_playlist_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ColumnScope.OfflinePlaylistDetail(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    playlist: OfflinePlaylist,
    revision: Long,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onItem: (OfflineProfile, MediaItem) -> Unit,
    onError: (Boolean) -> Unit,
) {
    val metadata = remember(playlist.metadata) { JSONObject(playlist.metadata) }
    val scope = rememberCoroutineScope()
    var offset by remember(playlist.id) { mutableIntStateOf(0) }
    var entries by remember(playlist.id) { mutableStateOf(emptyList<OfflinePlaylistEntry>()) }
    var addTracks by remember(playlist.id) { mutableStateOf(false) }
    val count by
        produceState(0, profile.id, playlist.id, revision) {
            value = runtime.database.offline().playlistEntryCount(profile.id, playlist.id)
        }
    val complete = playlist.complete && count == playlist.expectedCount

    LaunchedEffect(profile.id, playlist.id, offset, revision) {
        entries = runtime.playlists.entries(profile.id, playlist.id, offset = offset)
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onBack) {
            Icon(
                painterResource(LucideR.drawable.lucide_ic_arrow_left),
                stringResource(R.string.back),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(metadata.optString("name"), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(
                    R.string.offline_playlist_entry_count,
                    count,
                    playlist.expectedCount,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconButton(enabled = complete, onClick = onEdit) {
            Icon(
                painterResource(LucideR.drawable.lucide_ic_pencil),
                stringResource(R.string.offline_playlist_edit),
            )
        }
        IconButton(enabled = complete, onClick = onDelete) {
            Icon(
                painterResource(LucideR.drawable.lucide_ic_trash_2),
                stringResource(R.string.offline_playlist_delete),
            )
        }
    }
    val description = metadata.optString("description").takeIf { it.isNotBlank() && it != "null" }
    description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

    if (!complete) {
        Text(
            stringResource(R.string.offline_playlist_read_only),
            modifier = Modifier.padding(vertical = 8.dp),
        )
        val actions = LocalDownloadUi.current
        TextButton(
            enabled = actions != null,
            onClick = {
                actions
                    ?.savePlaylistMetadata
                    ?.invoke(
                        DownloadSpec(
                            playlist.id,
                            metadata.optString("name", playlist.id),
                            kind = "playlist_metadata",
                        )
                    )
            },
        ) {
            Text(stringResource(R.string.offline_playlist_save_membership))
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { addTracks = true }) {
                Icon(painterResource(LucideR.drawable.lucide_ic_plus), contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.offline_playlist_add_tracks))
            }
            Text(
                stringResource(R.string.offline_playlist_local_membership_note),
                modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    LazyColumn(Modifier.weight(1f)) {
        if (entries.isEmpty()) {
            item { Text(stringResource(R.string.offline_playlist_empty), Modifier.padding(16.dp)) }
        }
        items(entries, key = { it.track }) { entry ->
            val item by
                produceState<MediaItem?>(null, profile.id, entry.track, revision) {
                    value = runtime.catalog.item(profile.id, entry.track)
                }
            val availability by
                produceState(OfflineAvailability(0, 0), profile.id, entry.track, revision) {
                    value =
                        runCatching { runtime.catalog.availability(profile.id, entry.track) }
                            .getOrDefault(OfflineAvailability(0, 0))
                }
            ListItem(
                headlineContent = { Text(item?.name ?: entry.track) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (availability.downloaded > 0) R.string.offline_saved_badge
                            else R.string.offline_audio_not_downloaded
                        )
                    )
                },
                trailingContent = {
                    if (complete)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                enabled = offset + entries.indexOf(entry) > 0,
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                                runtime.playlists.moveTrack(
                                                    profile.id,
                                                    playlist.id,
                                                    entry.track,
                                                    offset + entries.indexOf(entry) - 1,
                                                )
                                            }
                                            .onFailure { failure ->
                                                if (failure is CancellationException) throw failure
                                                onError(true)
                                            }
                                    }
                                },
                            ) {
                                Icon(
                                    painterResource(LucideR.drawable.lucide_ic_arrow_up),
                                    stringResource(R.string.offline_playlist_move_up),
                                )
                            }
                            IconButton(
                                enabled = offset + entries.indexOf(entry) < count - 1,
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                                runtime.playlists.moveTrack(
                                                    profile.id,
                                                    playlist.id,
                                                    entry.track,
                                                    offset + entries.indexOf(entry) + 1,
                                                )
                                            }
                                            .onFailure { failure ->
                                                if (failure is CancellationException) throw failure
                                                onError(true)
                                            }
                                    }
                                },
                            ) {
                                Icon(
                                    painterResource(LucideR.drawable.lucide_ic_arrow_down),
                                    stringResource(R.string.offline_playlist_move_down),
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                                runtime.playlists.removeTrack(
                                                    profile.id,
                                                    playlist.id,
                                                    entry.track,
                                                )
                                            }
                                            .onFailure { failure ->
                                                if (failure is CancellationException) throw failure
                                                onError(true)
                                            }
                                    }
                                }
                            ) {
                                Icon(
                                    painterResource(LucideR.drawable.lucide_ic_x),
                                    stringResource(R.string.offline_playlist_remove_track),
                                )
                            }
                        }
                },
                modifier =
                    if (availability.downloaded > 0 && item != null)
                        Modifier.clickable { onItem(profile, requireNotNull(item)) }
                    else Modifier,
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    enabled = offset > 0,
                    onClick = {
                        offset = (offset - OfflinePlaylistRepository.PAGE_SIZE).coerceAtLeast(0)
                    },
                ) {
                    Text(stringResource(R.string.offline_previous_page))
                }
                TextButton(
                    enabled = offset + entries.size < count,
                    onClick = { offset += OfflinePlaylistRepository.PAGE_SIZE },
                ) {
                    Text(stringResource(R.string.offline_next_page))
                }
            }
        }
    }

    if (addTracks)
        Dialog(onDismissRequest = { addTracks = false }) {
            Surface(shape = MaterialTheme.shapes.large) {
                OfflinePlaylistTrackPicker(
                    runtime = runtime,
                    profile = profile,
                    playlist = playlist,
                    onDismiss = { addTracks = false },
                    onError = onError,
                )
            }
        }
}

@Composable
private fun OfflinePlaylistTrackPicker(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    playlist: OfflinePlaylist,
    onDismiss: () -> Unit,
    onError: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var offset by remember { mutableIntStateOf(0) }
    var tracks by remember { mutableStateOf(emptyList<MediaItem>()) }
    var groups by remember { mutableStateOf(emptyList<MediaItem>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var existing by remember { mutableStateOf(emptySet<String>()) }
    var groupsMode by remember { mutableStateOf(false) }
    var addedFromGroup by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(profile.id, playlist.id) {
        existing =
            runtime.playlists.entries(profile.id, playlist.id, limit = 10_000).mapTo(HashSet()) {
                it.track
            }
    }
    LaunchedEffect(profile.id, query, offset) {
        tracks = runtime.playlists.knownTracks(profile.id, query, offset = offset)
    }
    LaunchedEffect(profile.id, query, offset, groupsMode) {
        if (groupsMode)
            groups = runtime.playlists.knownMusicGroups(profile.id, query, offset = offset)
    }

    Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).padding(20.dp)) {
        Text(
            stringResource(R.string.offline_playlist_add_tracks),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.offline_playlist_local_membership_note),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !groupsMode,
                onClick = {
                    groupsMode = false
                    offset = 0
                },
                label = { Text(stringResource(R.string.offline_playlist_add_tracks)) },
            )
            FilterChip(
                selected = groupsMode,
                onClick = {
                    groupsMode = true
                    offset = 0
                },
                label = { Text(stringResource(R.string.offline_playlist_add_from_group)) },
            )
        }
        OutlinedTextField(
            query,
            {
                query = it
                offset = 0
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.offline_search)) },
            singleLine = true,
        )
        if (addedFromGroup >= 0)
            Text(
                stringResource(R.string.offline_playlist_group_added_count, addedFromGroup),
                style = MaterialTheme.typography.bodySmall,
            )
        LazyColumn(Modifier.weight(1f)) {
            if (groupsMode) {
                if (groups.isEmpty())
                    item {
                        Text(
                            stringResource(R.string.offline_playlist_no_known_groups),
                            Modifier.padding(16.dp),
                        )
                    }
                items(groups, key = { "group:${it.id}" }) { group ->
                    val kind =
                        if (group.type == "MusicArtist") R.string.offline_audio_artists
                        else R.string.offline_audio_albums
                    ListItem(
                        headlineContent = { Text(group.name) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.offline_playlist_group_kind_partial,
                                    stringResource(kind),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            TextButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        try {
                                            addedFromGroup =
                                                runtime.playlists.addKnownTracksFromGroup(
                                                    profile.id,
                                                    playlist.id,
                                                    group.id,
                                                )
                                            existing =
                                                runtime.playlists
                                                    .entries(
                                                        profile.id,
                                                        playlist.id,
                                                        limit = 10_000,
                                                    )
                                                    .mapTo(HashSet()) { it.track }
                                            onError(false)
                                        } catch (failure: Exception) {
                                            if (failure is CancellationException) throw failure
                                            onError(true)
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.offline_playlist_add_cached_tracks))
                            }
                        },
                    )
                }
            } else {
                if (tracks.isEmpty())
                    item {
                        Text(
                            stringResource(R.string.offline_playlist_no_known_tracks),
                            Modifier.padding(16.dp),
                        )
                    }
                items(tracks, key = { it.id }) { item ->
                    val availability by
                        produceState(OfflineAvailability(0, 0), item.id) {
                            value =
                                runCatching { runtime.catalog.availability(profile.id, item.id) }
                                    .getOrDefault(OfflineAvailability(0, 0))
                        }
                    val isExisting = item.id in existing
                    ListItem(
                        headlineContent = { Text(item.name) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (availability.downloaded > 0) R.string.offline_saved_badge
                                    else R.string.offline_audio_not_downloaded
                                )
                            )
                        },
                        trailingContent = {
                            Checkbox(
                                checked = isExisting || item.id in selected,
                                enabled = !isExisting,
                                onCheckedChange = { checked ->
                                    selected =
                                        if (checked) selected + item.id else selected - item.id
                                },
                            )
                        },
                        modifier =
                            Modifier.clickable {
                                if (!isExisting)
                                    selected =
                                        if (item.id in selected) selected - item.id
                                        else selected + item.id
                            },
                    )
                }
            }
        }
        val pageCount = if (groupsMode) groups.size else tracks.size
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(
                enabled = offset > 0,
                onClick = {
                    offset = (offset - OfflinePlaylistRepository.PAGE_SIZE).coerceAtLeast(0)
                },
            ) {
                Text(stringResource(R.string.offline_previous_page))
            }
            TextButton(
                enabled = pageCount == OfflinePlaylistRepository.PAGE_SIZE,
                onClick = { offset += OfflinePlaylistRepository.PAGE_SIZE },
            ) {
                Text(stringResource(R.string.offline_next_page))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            FilledTonalButton(
                enabled = !groupsMode && selected.isNotEmpty() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            runtime.playlists.addTracks(profile.id, playlist.id, selected.toList())
                            onError(false)
                            onDismiss()
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            onError(true)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.offline_playlist_add_selected, selected.size))
            }
        }
    }
}

@Composable
private fun PlaylistDetailsDialog(
    title: String,
    initialName: String,
    initialDescription: String,
    onDismiss: () -> Unit,
    onSave: (String, String?) -> Unit,
) {
    var name by remember(title, initialName) { mutableStateOf(initialName) }
    var description by remember(title, initialDescription) { mutableStateOf(initialDescription) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.offline_playlist_name)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    description,
                    { description = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.offline_playlist_description)) },
                    minLines = 2,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, description) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

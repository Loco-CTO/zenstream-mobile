package com.zenstream.zenstreammobile.offline

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.CatalogException
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class DownloadUiActions(
    val runtime: OfflineRuntime,
    val request: (DownloadSpec) -> Unit,
    val openDownloads: () -> Unit,
    val savePlaylistMetadata: (DownloadSpec) -> Unit,
    val downloadMissing: (OfflineSelection) -> Unit,
    val playSaved: (OfflineProfile, MediaItem) -> Unit,
)

val LocalDownloadUi = staticCompositionLocalOf<DownloadUiActions?> { null }

@Composable
fun DownloadButton(item: MediaItem?, modifier: Modifier = Modifier) {
    if (item == null) return
    DownloadButton(
        DownloadSpec(
            item.id,
            item.name,
            type = item.type,
            durationSeconds = item.durationSeconds ?: item.runtimeTicks?.div(10000000.0),
        ),
        modifier,
    )
}

@Composable
fun DownloadButton(spec: DownloadSpec, modifier: Modifier = Modifier) {
    val actions = LocalDownloadUi.current ?: return
    IconButton(onClick = { actions.request(spec) }, modifier = modifier) {
        Icon(
            painterResource(LucideR.drawable.lucide_ic_download),
            stringResource(R.string.offline_download_item, spec.title),
        )
    }
}

@Composable
fun DownloadedBadge(item: MediaItem, modifier: Modifier = Modifier) {
    val actions = LocalDownloadUi.current ?: return
    val connection by actions.runtime.connection.state.collectAsState()
    val revision by actions.runtime.catalogRevision.collectAsState()
    val count by
        produceState(0, item.id, connection.profile, revision) {
            value =
                connection.profile?.let { profile ->
                    runCatching {
                            actions.runtime.catalog.availability(profile, item.id).downloaded
                        }
                        .getOrDefault(0)
                } ?: 0
        }
    if (count > 0)
        Surface(
            modifier,
            color = MaterialTheme.colorScheme.surface.copy(alpha = .9f),
            shape = MaterialTheme.shapes.small,
        ) {
            Icon(
                painterResource(LucideR.drawable.lucide_ic_download),
                stringResource(R.string.offline_saved_badge),
                modifier = Modifier.padding(5.dp).size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
}

@Composable
fun OfflineDownloadHost(
    runtime: OfflineRuntime?,
    session: AuthSession?,
    openRequested: Boolean = false,
    onOpenConsumed: () -> Unit = {},
    onSavedItem: ((OfflineProfile, MediaItem) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var selected by remember { mutableStateOf<DownloadSpec?>(null) }
    var open by remember { mutableStateOf(false) }
    var localMusic by remember { mutableStateOf<Pair<OfflineProfile, MediaItem>?>(null) }
    var localQueue by remember { mutableStateOf<List<MediaItem>?>(null) }
    val context = LocalContext.current
    val playSaved: (OfflineProfile, MediaItem) -> Unit =
        onSavedItem
            ?: { profile, item ->
                if (item.isOfflineMusic()) {
                    localQueue = null
                    localMusic = profile to item
                } else if (item.type in setOf("Movie", "Episode")) {
                    com.zenstream.zenstreammobile.launchPlayback(
                        context,
                        item.id,
                        item.name,
                        offlineProfileId = profile.id,
                    )
                }
            }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val actions =
        if (runtime != null && OfflineRuntime.ENABLED)
            DownloadUiActions(
                runtime,
                request = {
                    if (session != null && OfflineNetworkPolicy.allowed) selected = it
                    else open = true
                },
                openDownloads = { open = true },
                savePlaylistMetadata = { spec ->
                    if (session != null)
                        scope.launch {
                            try {
                                runtime.enqueuePlaylistMetadata(context, session, spec)
                                snackbar.showSnackbar(
                                    resources.getString(R.string.offline_metadata_queued)
                                )
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                snackbar.showSnackbar(
                                    resources.getString(R.string.offline_request_failed)
                                )
                            }
                        }
                },
                downloadMissing = { selection ->
                    if (session != null)
                        scope.launch {
                            try {
                                val options =
                                    JSONObject(selection.options).put("skipExisting", true)
                                runtime.enqueueDownload(
                                    context,
                                    session,
                                    DownloadSpec(
                                        selection.rootId,
                                        selection.title,
                                        selection.kind,
                                        options.optString("mediaType"),
                                    ),
                                    options,
                                )
                                snackbar.showSnackbar(resources.getString(R.string.offline_added))
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                snackbar.showSnackbar(
                                    resources.getString(R.string.offline_request_failed)
                                )
                            }
                        }
                },
                playSaved = playSaved,
            )
        else null
    LaunchedEffect(openRequested) {
        if (openRequested && actions != null) {
            open = true
            onOpenConsumed()
        }
    }
    CompositionLocalProvider(LocalDownloadUi provides actions) {
        Box(Modifier.fillMaxSize()) {
            content()
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            )
        }
        if (open && runtime != null) {
            Dialog(
                onDismissRequest = { open = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(Modifier.fillMaxSize()) {
                    DownloadsScreen(
                        runtime,
                        onBack = { open = false },
                        onOnline = { open = false },
                        onItem = playSaved,
                    )
                }
            }
        }
        localMusic?.let { (profile, item) ->
            if (runtime != null)
                Dialog(
                    onDismissRequest = { localMusic = null },
                    properties = DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    Surface(Modifier.fillMaxSize()) {
                        OfflineSavedMusicScreen(
                            runtime,
                            profile,
                            item,
                            onBack = { localMusic = null },
                            initialQueue = localQueue,
                            onItem = { next, queue ->
                                localQueue = queue
                                localMusic = profile to next
                            },
                        )
                    }
                }
        }
        selected?.let { spec ->
            if (runtime != null && session != null)
                DownloadSelectionSheet(
                    runtime,
                    session,
                    spec,
                    onDismiss = { selected = null },
                    onQueued = { started ->
                        selected = null
                        scope.launch {
                            snackbar.showSnackbar(
                                resources.getString(
                                    if (started) R.string.offline_added
                                    else R.string.offline_resume_needed
                                )
                            )
                        }
                    },
                )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSelectionSheet(
    runtime: OfflineRuntime,
    session: AuthSession,
    spec: DownloadSpec,
    onDismiss: () -> Unit,
    onQueued: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var capabilities by remember(spec.id) { mutableStateOf<JSONObject?>(null) }
    var source by remember(spec.id) { mutableStateOf<JSONObject?>(null) }
    var quality by remember(spec.id) { mutableStateOf("original") }
    var audio by remember(spec.id) { mutableStateOf<Int?>(null) }
    var subtitles by remember(spec.id) { mutableStateOf(emptySet<Int>()) }
    var audioLanguage by remember(spec.id) { mutableStateOf("") }
    var subtitleLanguages by remember(spec.id) { mutableStateOf("") }
    var loading by remember(spec.id) { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(spec.id) {
        try {
            capabilities = runtime.catalog.discover(session).second
            if (spec.type?.lowercase() in setOf("movie", "episode", "audio", "track"))
                source = runtime.sourceDetails(session, spec.id)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            error =
                if ((failure as? CatalogException)?.statusCode in setOf(404, 405, 501))
                    R.string.offline_not_supported
                else R.string.offline_request_failed
        } finally {
            loading = false
        }
    }
    val streams = source?.optJSONArray("streams")?.objects().orEmpty()
    val hdr = streams.any { it.optString("color_transfer") in setOf("smpte2084", "arib-std-b67") }
    val profiles = capabilities?.let { downloadProfiles(spec, it, hdr) }.orEmpty()
    val selectedBitmap =
        quality != "original" &&
            streams.any {
                it.optInt("index") in subtitles &&
                    it.optString("codec_name") !in downloadableTextSubtitles
            }
    val estimated =
        estimateDownloadBytes(
            quality,
            source?.let {
                JSONObject(it.toString()).apply {
                    if (!has("durationSeconds")) put("durationSeconds", spec.durationSeconds)
                }
            },
        )
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.offline_download_item, spec.title),
                style = MaterialTheme.typography.titleLarge,
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            profiles.forEach { profile ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !busy) { quality = profile },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = quality == profile, onClick = { quality = profile })
                    Text(qualityLabel(profile))
                }
            }
            Text(
                if (estimated != null)
                    stringResource(
                        R.string.offline_size_estimate,
                        Formatter.formatFileSize(context, estimated),
                    )
                else stringResource(R.string.offline_size_unknown),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(
                    if (quality == "original") R.string.offline_original_tracks
                    else R.string.offline_portable_tracks
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (source != null) {
                Row(
                    Modifier.fillMaxWidth().clickable { audio = null },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = audio == null, onClick = { audio = null })
                    Text(stringResource(R.string.offline_default_audio))
                }
                streams
                    .filter { streamType(it) == "audio" }
                    .forEach { stream ->
                        val index = stream.getInt("index")
                        Row(
                            Modifier.fillMaxWidth().clickable { audio = index },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = audio == index, onClick = { audio = index })
                            Text(streamLabel(stream))
                        }
                    }
                streams
                    .filter { streamType(it) == "subtitle" }
                    .forEach { stream ->
                        val index = stream.getInt("index")
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                subtitles =
                                    if (index in subtitles) subtitles - index
                                    else if (subtitles.size < 8) subtitles + index else subtitles
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = index in subtitles,
                                onCheckedChange = {
                                    subtitles =
                                        if (it && subtitles.size < 8) subtitles + index
                                        else subtitles - index
                                },
                            )
                            Text(streamLabel(stream))
                        }
                    }
            } else if (!loading && capabilities != null) {
                OutlinedTextField(
                    audioLanguage,
                    { audioLanguage = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.offline_audio_language)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    subtitleLanguages,
                    { subtitleLanguages = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.offline_subtitle_languages)) },
                    singleLine = true,
                )
                Text(
                    stringResource(R.string.offline_language_help),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (selectedBitmap)
                Text(
                    stringResource(R.string.offline_bitmap_original),
                    color = MaterialTheme.colorScheme.error,
                )
            Text(
                stringResource(R.string.offline_snapshot_help),
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                enabled = !loading && !busy && profiles.isNotEmpty() && !selectedBitmap,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val options =
                                JSONObject()
                                    .put("profile", quality)
                                    .put("priorityItem", spec.id)
                                    .put("language", runtime.sessionStore.metadataLanguage.first())
                            source
                                ?.optString("id")
                                ?.takeIf { it.isNotBlank() }
                                ?.let { options.put("sourceId", it) }
                            audio?.let { options.put("audioStreamId", it) }
                            if (subtitles.isNotEmpty())
                                options.put("subtitleStreamIndices", JSONArray(subtitles.sorted()))
                            if (audioLanguage.isNotBlank())
                                options.put("audioLanguage", audioLanguage.trim())
                            val languages =
                                subtitleLanguages
                                    .split(',')
                                    .map(String::trim)
                                    .filter(String::isNotBlank)
                                    .distinct()
                            require(languages.size <= 8)
                            if (languages.isNotEmpty())
                                options.put("subtitleLanguages", JSONArray(languages))
                            onQueued(runtime.enqueueDownload(context, session, spec, options))
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            error = R.string.offline_request_failed
                            busy = false
                        }
                    }
                },
            ) {
                Text(
                    stringResource(
                        if (busy) R.string.offline_preparing else R.string.offline_download
                    )
                )
            }
        }
    }
}

@Composable
internal fun qualityLabel(profile: String): String =
    stringResource(
        when (profile) {
            "video-720p" -> R.string.offline_quality_720
            "video-1080p" -> R.string.offline_quality_1080
            "audio-128k" -> R.string.offline_quality_128
            "audio-256k" -> R.string.offline_quality_256
            else -> R.string.offline_quality_original
        }
    )

private fun streamLabel(stream: JSONObject): String =
    listOf(
            streamLanguage(stream),
            stream.optJSONObject("tags")?.optString("title").orEmpty(),
            stream.optString("codec_name"),
        )
        .filter { it.isNotBlank() }
        .joinToString(" · ")

@Composable
fun DownloadsScreen(
    runtime: OfflineRuntime,
    onBack: (() -> Unit)? = null,
    onOnline: () -> Unit,
    onItem: (OfflineProfile, MediaItem) -> Unit = { _, _ -> },
) {
    val profiles by runtime.database.offline().profiles().collectAsState(initial = emptyList())
    val connection by runtime.connection.state.collectAsState()
    val selected =
        profiles.firstOrNull { it.id == connection.profile }
            ?: profiles.firstOrNull { !it.locked }
            ?: profiles.firstOrNull()
    var tab by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            onBack?.let {
                IconButton(onClick = it) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_arrow_left),
                        stringResource(R.string.back),
                    )
                }
            }
            Text(
                stringResource(R.string.offline_downloads),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            TextButton(
                onClick = {
                    scope.launch {
                        runtime.connection.setMode(ConnectionMode.AUTOMATIC)
                        onOnline()
                    }
                }
            ) {
                Text(stringResource(R.string.offline_go_online))
            }
        }
        Text(
            stringResource(
                if (connection.mode == ConnectionMode.OFFLINE) R.string.offline_mode_active
                else if (
                    connection.reachability in
                        setOf(ServerReachability.UNREACHABLE, ServerReachability.CERTIFICATE_ERROR)
                )
                    R.string.offline_server_unavailable
                else R.string.offline_local_available
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (profiles.size > 1) {
            var choosing by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { choosing = true }) { Text(selected?.displayName.orEmpty()) }
                DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    profile.displayName +
                                        " · " +
                                        android.net.Uri.parse(profile.serverUrl).host.orEmpty()
                                )
                            },
                            onClick = {
                                choosing = false
                                scope.launch { runtime.connection.selectProfile(profile.id) }
                            },
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.offline_saved, R.string.offline_transfers, R.string.settings)
                .forEachIndexed { index, label ->
                    FilterChip(
                        selected = tab == index,
                        onClick = { tab = index },
                        label = { Text(stringResource(label)) },
                    )
                }
        }
        if (selected == null) {
            Text(
                stringResource(R.string.offline_empty),
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else if (selected.locked) {
            Text(
                stringResource(R.string.offline_locked),
                modifier = Modifier.padding(vertical = 24.dp),
            )
        } else
            when (tab) {
                0 -> SavedDownloadList(runtime, selected, onItem)
                1 -> TransferDownloadList(runtime, selected)
                else -> DownloadSettings(runtime, selected)
            }
        if (selected != null && !selected.locked && tab == 0)
            OfflineMusicMiniPlayer(selected) { onItem(selected, it) }
    }
}

@Composable
private fun ColumnScope.SavedDownloadList(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    onItem: (OfflineProfile, MediaItem) -> Unit,
) {
    var category by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var offset by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(emptyList<MediaItem>()) }
    var continueWatching by remember(profile.id) { mutableStateOf(emptyList<MediaItem>()) }
    var nextUp by remember(profile.id) { mutableStateOf(emptyList<MediaItem>()) }
    val scope = rememberCoroutineScope()
    val revision by runtime.catalogRevision.collectAsState()
    LaunchedEffect(profile.id, profile.locked, category, query, offset, revision) {
        try {
            rows = runtime.catalog.search(profile.id, category, query, offset)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            rows = emptyList()
        }
    }
    LaunchedEffect(profile.id, profile.locked, category, query, revision) {
        if (query.isNotBlank() || category !in setOf("", "tv")) {
            continueWatching = emptyList()
            nextUp = emptyList()
        } else {
            continueWatching = runtime.playbackState.continueWatching(profile.id)
            nextUp = runtime.playbackState.nextUp(profile.id)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
                "movies" to R.string.offline_movies,
                "tv" to R.string.offline_tv,
                "music" to R.string.offline_music,
            )
            .forEach { (key, label) ->
                FilterChip(
                    selected = category == key,
                    onClick = {
                        category = if (category == key) "" else key
                        offset = 0
                    },
                    label = { Text(stringResource(label)) },
                )
            }
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
    LazyColumn(Modifier.weight(1f)) {
        if (continueWatching.isNotEmpty()) {
            item(key = "offline:continue:heading") {
                Text(
                    stringResource(R.string.offline_continue_watching),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(continueWatching, key = { "offline:continue:${it.id}" }) { item ->
                ListItem(
                    headlineContent = { Text(item.name) },
                    supportingContent = { Text(stringResource(R.string.offline_continue_hint)) },
                    trailingContent = { DownloadedBadge(item) },
                    modifier = Modifier.clickable { onItem(profile, item) },
                )
            }
        }
        if (nextUp.isNotEmpty()) {
            item(key = "offline:next-up:heading") {
                Text(
                    stringResource(R.string.offline_next_up),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(nextUp, key = { "offline:next-up:${it.id}" }) { item ->
                ListItem(
                    headlineContent = { Text(item.name) },
                    supportingContent = { Text(item.seriesName.orEmpty()) },
                    trailingContent = { DownloadedBadge(item) },
                    modifier = Modifier.clickable { onItem(profile, item) },
                )
            }
        }
        if (rows.isEmpty() && continueWatching.isEmpty() && nextUp.isEmpty())
            item {
                Text(stringResource(R.string.offline_empty), Modifier.padding(vertical = 24.dp))
            }
        items(rows, key = { it.id }) { item ->
            val availability by
                produceState(OfflineAvailability(0, 0), item.id, revision) {
                    try {
                        value = runtime.catalog.availability(profile.id, item.id)
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        value = OfflineAvailability(0, 0)
                    }
                }
            val watchedCounts by
                produceState(OfflineWatchedCounts(0, 0), item.id, revision) {
                    value =
                        if (item.type.equals("Series", true) || item.type.equals("Season", true))
                            runCatching {
                                    runtime.playbackState.downloadedEpisodeWatchCounts(
                                        profile.id,
                                        item.id,
                                    )
                                }
                                .getOrDefault(OfflineWatchedCounts(0, 0))
                        else OfflineWatchedCounts(0, 0)
                }
            ListItem(
                headlineContent = { Text(item.name) },
                supportingContent = {
                    Column {
                        Text(
                            stringResource(
                                R.string.offline_saved_count,
                                availability.downloaded,
                                availability.total,
                            )
                        )
                        if (watchedCounts.total > 0)
                            Text(
                                stringResource(
                                    R.string.offline_downloaded_watched_count,
                                    watchedCounts.watched,
                                    watchedCounts.total,
                                )
                            )
                    }
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        DownloadedBadge(item)
                        if (watchedCounts.total > 0)
                            TextButton(
                                onClick = {
                                    val markWatched = watchedCounts.watched < watchedCounts.total
                                    scope.launch {
                                        runtime.playbackState.markDownloadedEpisodes(
                                            profile.id,
                                            item.id,
                                            markWatched,
                                        )
                                    }
                                }
                            ) {
                                Text(
                                    stringResource(
                                        if (watchedCounts.watched < watchedCounts.total)
                                            R.string.offline_mark_downloaded_watched
                                        else R.string.offline_mark_downloaded_unwatched
                                    )
                                )
                            }
                    }
                },
                modifier = Modifier.clickable { onItem(profile, item) },
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
}

@Composable
private fun ColumnScope.TransferDownloadList(runtime: OfflineRuntime, profile: OfflineProfile) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var offset by remember { mutableIntStateOf(0) }
    var groupOffset by remember { mutableIntStateOf(0) }
    var actionFailed by remember { mutableStateOf(false) }
    suspend fun perform(action: suspend () -> Unit) {
        actionFailed = false
        try {
            action()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            actionFailed = true
        }
    }
    val actions = LocalDownloadUi.current
    val transfers by
        remember(profile.id, offset) {
                runtime.database.offline().transferPage(profile.id, 100, offset)
            }
            .collectAsState(initial = emptyList())
    val selections by
        remember(profile.id, groupOffset) {
                runtime.database.offline().selectionPage(profile.id, 100, groupOffset)
            }
            .collectAsState(initial = emptyList())
    val connection by runtime.connection.state.collectAsState()
    val online = connection.mode == ConnectionMode.AUTOMATIC
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(
            enabled = online,
            onClick = {
                scope.launch {
                    perform {
                        runtime.resumeDownloads(profile.id)
                        DownloadExecution.start(context, profile.id)
                    }
                }
            },
        ) {
            Text(stringResource(R.string.offline_resume_all))
        }
        TextButton(
            onClick = {
                scope.launch {
                    perform {
                        runtime.database.offline().pauseAllTransfers(profile.id)
                        runtime.database.offline().pauseEnumeration(profile.id)
                        DownloadExecution.stop(context)
                    }
                }
            }
        ) {
            Text(stringResource(R.string.offline_pause))
        }
    }
    if (actionFailed)
        Text(
            stringResource(R.string.offline_request_failed),
            color = MaterialTheme.colorScheme.error,
        )
    LazyColumn(Modifier.weight(1f)) {
        items(selections, key = { "selection:${it.id}" }) { selection ->
            val totals by
                remember(profile.id, selection.id) {
                        runtime.database.offline().selectionTotals(profile.id, selection.id)
                    }
                    .collectAsState(initial = DownloadTotals(0, 0, 0, 0))
            ListItem(
                headlineContent = { Text(selection.title) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.offline_selection_count, selection.itemCount))
                        Text(
                            stringResource(
                                when {
                                    selection.error != null -> R.string.offline_failed
                                    selection.state.startsWith("paused_") -> R.string.offline_paused
                                    selection.state == "metadata_complete" ->
                                        R.string.offline_metadata_saved
                                    selection.state in
                                        setOf(
                                            "enumerating",
                                            "ready",
                                            "queueing",
                                            "metadata_only",
                                        ) -> R.string.offline_preparing
                                    else -> R.string.offline_transfer_progress
                                },
                                totals.complete,
                                totals.total,
                            )
                        )
                    }
                },
                trailingContent = {
                    Row {
                        IconButton(
                            enabled = online && selection.kind != "playlist_metadata",
                            onClick = {
                                actions?.downloadMissing?.invoke(selection)
                            },
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_list_plus),
                                stringResource(R.string.offline_download_missing),
                            )
                        }
                        IconButton(
                            onClick = {
                                scope.launch {
                                    perform {
                                        runtime.storage.removeSelection(profile.id, selection.id)
                                    }
                                }
                            }
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_trash_2),
                                stringResource(R.string.offline_remove_selection),
                            )
                        }
                    }
                },
            )
        }
        item(key = "selection-pages") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    enabled = groupOffset > 0,
                    onClick = { groupOffset = (groupOffset - 100).coerceAtLeast(0) },
                ) {
                    Text(stringResource(R.string.offline_previous_selections))
                }
                TextButton(enabled = selections.size == 100, onClick = { groupOffset += 100 }) {
                    Text(stringResource(R.string.offline_next_selections))
                }
            }
        }
        items(transfers, key = { it.id }) { transfer ->
            val item by
                produceState<MediaItem?>(null, profile.id, transfer.item) {
                    value = runtime.catalog.item(profile.id, transfer.item)
                }
            ListItem(
                headlineContent = {
                    Text(item?.name ?: stringResource(R.string.offline_unknown_item))
                },
                supportingContent = {
                    Column {
                        Text(transferStateLabel(transfer))
                        if (transfer.total > 0)
                            LinearProgressIndicator(
                                progress = {
                                    (transfer.bytes.toDouble() / transfer.total)
                                        .coerceIn(0.0, 1.0)
                                        .toFloat()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        Text(
                            Formatter.formatFileSize(context, transfer.bytes) +
                                " / " +
                                if (transfer.total > 0)
                                    Formatter.formatFileSize(context, transfer.total)
                                else stringResource(R.string.offline_size_unknown)
                        )
                    }
                },
                trailingContent = {
                    Row {
                        if (transfer.state in setOf("paused", "failed", "cancelled")) {
                            IconButton(
                                enabled = online,
                                onClick = {
                                    scope.launch {
                                        runtime.downloads.retry(profile.id, transfer.id)
                                        DownloadExecution.start(context, profile.id)
                                    }
                                },
                            ) {
                                Icon(
                                    painterResource(LucideR.drawable.lucide_ic_rotate_cw),
                                    stringResource(R.string.retry),
                                )
                            }
                        } else if (transfer.state != "complete") {
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        runtime.downloads.pause(profile.id, transfer.id)
                                    }
                                }
                            ) {
                                Icon(
                                    painterResource(LucideR.drawable.lucide_ic_pause),
                                    stringResource(R.string.offline_pause),
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        runtime.downloads.cancel(profile.id, transfer.id)
                                    }
                                }
                            ) {
                                Icon(
                                    painterResource(LucideR.drawable.lucide_ic_x),
                                    stringResource(R.string.cancel),
                                )
                            }
                        }
                    }
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
                TextButton(enabled = transfers.size == 100, onClick = { offset += 100 }) {
                    Text(stringResource(R.string.offline_next_page))
                }
            }
        }
    }
}

@Composable
private fun transferStateLabel(transfer: OfflineTransfer): String =
    stringResource(
        when {
            transfer.error == "authentication_required" -> R.string.offline_authentication_needed
            transfer.error == "source_changed" -> R.string.offline_restart_needed
            transfer.state == "complete" -> R.string.offline_saved
            transfer.state == "paused" -> R.string.offline_paused
            transfer.state == "cancelled" -> R.string.offline_cancelled
            transfer.state == "failed" -> R.string.offline_failed
            transfer.state in setOf("preparing", "resolving") -> R.string.offline_preparing
            transfer.state in setOf("verifying", "publishing") -> R.string.offline_verifying
            transfer.state == "transferring" -> R.string.offline_downloading
            else -> R.string.offline_queued
        }
    )

@Composable
private fun DownloadSettings(runtime: OfflineRuntime, profile: OfflineProfile) {
    val used by runtime.database.offline().storageUsed(profile.id).collectAsState(initial = 0L)
    val mode by runtime.connection.state.collectAsState()
    val metered by runtime.connection.allowMetered.collectAsState()
    val limit by runtime.connection.downloadLimitBytes.collectAsState()
    var text by
        remember(limit) {
            mutableStateOf(limit?.toDouble()?.div(OFFLINE_FREE_RESERVE)?.toString().orEmpty())
        }
    var invalid by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val modeLabel = stringResource(R.string.offline_use_mode)
    val meteredLabel = stringResource(R.string.offline_allow_metered)
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.offline_storage_used, Formatter.formatFileSize(context, used)))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.offline_use_mode), Modifier.weight(1f))
            Switch(
                mode.mode == ConnectionMode.OFFLINE,
                modifier = Modifier.semantics { contentDescription = modeLabel },
                onCheckedChange = { offline ->
                    scope.launch {
                        runtime.connection.setMode(
                            if (offline) ConnectionMode.OFFLINE else ConnectionMode.AUTOMATIC
                        )
                    }
                },
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.offline_allow_metered), Modifier.weight(1f))
            Switch(
                metered,
                modifier = Modifier.semantics { contentDescription = meteredLabel },
                onCheckedChange = { scope.launch { runtime.connection.setAllowMetered(it) } },
            )
        }
        OutlinedTextField(
            text,
            {
                text = it
                invalid = false
            },
            label = { Text(stringResource(R.string.offline_download_limit)) },
            supportingText = { Text(stringResource(R.string.offline_storage_help)) },
            isError = invalid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val value = text.toDoubleOrNull()
                if (
                    text.isNotBlank() &&
                        (value == null || !value.isFinite() || value < 0 || value > 1048576)
                )
                    invalid = true
                else
                    scope.launch {
                        runtime.connection.setDownloadLimit(
                            value?.times(OFFLINE_FREE_RESERVE)?.toLong()
                        )
                    }
            }
        ) {
            Text(stringResource(R.string.save))
        }
    }
}

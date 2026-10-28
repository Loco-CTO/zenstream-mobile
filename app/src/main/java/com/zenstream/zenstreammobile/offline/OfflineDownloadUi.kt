package com.zenstream.zenstreammobile.offline

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
    val playSavedQueue: ((OfflineProfile, MediaItem, List<MediaItem>) -> Unit)? = null,
    val canDownload: (OfflineProfile) -> Boolean = { false },
)

val LocalDownloadUi = staticCompositionLocalOf<DownloadUiActions?> { null }

internal fun offlineCapabilityErrorMessage(failure: Exception): Int =
    when {
        failure is OfflineDownloadsUnsupportedException -> R.string.offline_not_supported
        (failure as? CatalogException)?.statusCode in setOf(404, 405, 501) ->
            R.string.offline_not_supported
        else -> R.string.offline_request_failed
    }

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
                canDownload = { profile ->
                    session != null &&
                        session.userId == profile.account &&
                        session.serverUrl == profile.serverUrl &&
                        OfflineNetworkPolicy.allowed &&
                        runtime.connection.state.value.authentication !=
                            OnlineAuthentication.NEEDS_SIGN_IN
                },
                playSavedQueue = { profile, item, queue ->
                    if (onSavedItem != null) onSavedItem(profile, item)
                    else {
                        localQueue = queue
                        localMusic = profile to item
                    }
                },
            )
        else null
    LaunchedEffect(openRequested) {
        if (openRequested && actions != null) {
            open = true
            onOpenConsumed()
        }
    }
    LaunchedEffect(runtime, session?.serverUrl, session?.userId) {
        if (runtime != null && session != null && OfflineRuntime.ENABLED)
            runtime.onAuthenticatedSession(session)
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
            error = offlineCapabilityErrorMessage(failure)
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

package com.zenstream.zenstreammobile.offline

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.ui.components.episodeCardSubtitle
import com.zenstream.zenstreammobile.ui.components.episodeCardTitle
import com.zenstream.zenstreammobile.ui.components.itemSubtitle
import com.zenstream.zenstreammobile.ui.components.musicAlbumArtist
import com.zenstream.zenstreammobile.ui.components.musicSubtitle
import com.zenstream.zenstreammobile.ui.components.progressPercent
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class DownloadPage {
    LIBRARY,
    PLAYLISTS,
    MANAGE,
    SETTINGS,
}

internal fun MediaItem.isOfflineContainer(): Boolean =
    type in setOf("Series", "Season", "BoxSet", "MusicArtist", "MusicAlbum")

internal suspend fun <T> offlineUiRead(fallback: T, read: suspend () -> T): T =
    try {
        read()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        fallback
    }

@Composable
fun DownloadsScreen(
    runtime: OfflineRuntime,
    onBack: (() -> Unit)? = null,
    onOnline: () -> Unit,
    onItem: (OfflineProfile, MediaItem) -> Unit = { _, _ -> },
) {
    val profileRows by runtime.database.offline().profiles().collectAsState(initial = null)
    val profiles = profileRows.orEmpty()
    val connection by runtime.connection.state.collectAsState()
    val profile =
        if (!connection.initialized) null
        else
            profiles.firstOrNull { it.id == connection.profile }
                ?: profiles.firstOrNull { !it.locked }
                ?: profiles.firstOrNull()
    var page by rememberSaveable { mutableStateOf(DownloadPage.LIBRARY) }
    var settingsFrom by rememberSaveable { mutableStateOf(DownloadPage.LIBRARY) }
    var containers by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var previousSearches by rememberSaveable { mutableStateOf(emptyList<Boolean>()) }
    var playlistId by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var navigationProfile by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(profile?.id) {
        if (profile != null) {
            if (navigationProfile != null && navigationProfile != profile.id) {
                containers = emptyList()
                previousSearches = emptyList()
                playlistId = null
                searching = false
                if (page != DownloadPage.SETTINGS) page = DownloadPage.LIBRARY
                settingsFrom = DownloadPage.LIBRARY
            }
            navigationProfile = profile.id
        }
    }
    val revision by runtime.catalogRevision.collectAsState()
    val containerId = containers.lastOrNull()
    var parent by remember(profile?.id, containerId) { mutableStateOf<MediaItem?>(null) }
    var loadingParent by remember(profile?.id, containerId) { mutableStateOf(containerId != null) }
    LaunchedEffect(profile?.id, containerId, revision) {
        loadingParent = parent == null && containerId != null
        parent =
            if (profile != null && containerId != null)
                offlineUiRead(null) { runtime.catalog.item(profile.id, containerId) }
            else null
        loadingParent = false
    }
    val savedState = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val actions = LocalDownloadUi.current
    val context = LocalContext.current
    val used by
        remember(profile?.id) {
                profile?.let { runtime.database.offline().storageUsed(it.id) } ?: flowOf(0L)
            }
            .collectAsState(initial = 0L)
    val playlists by
        remember(profile?.id) {
                profile?.let { runtime.playlists.playlists(it.id) } ?: flowOf(emptyList())
            }
            .collectAsState(initial = emptyList())
    val pending by
        remember(profile?.id) {
                profile?.let { runtime.database.offline().pendingDownloadCount(it.id) } ?: flowOf(0)
            }
            .collectAsState(initial = 0)
    fun back() {
        when {
            page == DownloadPage.LIBRARY && searching -> searching = false
            page == DownloadPage.SETTINGS -> page = settingsFrom
            page == DownloadPage.PLAYLISTS && playlistId != null -> playlistId = null
            page != DownloadPage.LIBRARY -> page = DownloadPage.LIBRARY
            containers.isNotEmpty() -> {
                containers = containers.dropLast(1)
                searching = previousSearches.lastOrNull() ?: false
                previousSearches = previousSearches.dropLast(1)
            }
            else -> onBack?.invoke()
        }
    }
    BackHandler(page != DownloadPage.LIBRARY || containers.isNotEmpty() || searching) { back() }
    suspend fun albumTracks(album: MediaItem): List<MediaItem> {
        val tracks = mutableListOf<MediaItem>()
        var offset = 0
        do {
            val batch =
                runtime.catalog.browseDownloads(requireNotNull(profile).id, null, "", album, offset)
            tracks += batch.filter { it.type == "Audio" }
            offset += batch.size
        } while (batch.size == 100)
        return tracks.distinctBy { it.id }
    }
    fun play(item: MediaItem) {
        val retained = profile ?: return
        if (item.type != "Audio" || actions?.playSavedQueue == null) {
            onItem(retained, item)
            return
        }
        scope.launch {
            val album =
                (item.albumId ?: item.parentId)
                    ?.let { offlineUiRead(null) { runtime.catalog.item(retained.id, it) } }
                    ?.takeIf { it.type == "MusicAlbum" }
            val queue =
                album
                    ?.let { offlineUiRead(emptyList()) { albumTracks(it) } }
                    .orEmpty()
                    .ifEmpty { listOf(item) }
            actions.playSavedQueue.invoke(retained, item, queue)
        }
    }
    val title =
        when (page) {
            DownloadPage.LIBRARY -> parent?.name ?: stringResource(R.string.offline_downloads)
            DownloadPage.PLAYLISTS ->
                playlists
                    .firstOrNull { it.id == playlistId }
                    ?.let {
                        JSONObject(it.metadata).optString("name")
                    } ?: stringResource(R.string.offline_playlists)
            DownloadPage.MANAGE -> stringResource(R.string.offline_manage_downloads)
            DownloadPage.SETTINGS -> stringResource(R.string.offline_download_settings)
        }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null || page != DownloadPage.LIBRARY || containers.isNotEmpty()) {
                    IconButton(onClick = ::back) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_arrow_left),
                            stringResource(R.string.back),
                        )
                    }
                } else Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (page == DownloadPage.LIBRARY && parent == null && profile != null) {
                        Text(
                            stringResource(
                                R.string.offline_storage_used,
                                Formatter.formatFileSize(context, used),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (page == DownloadPage.LIBRARY && profile != null && !profile.locked) {
                    IconButton(onClick = { searching = !searching }) {
                        Icon(
                            painterResource(
                                if (searching) LucideR.drawable.lucide_ic_x
                                else LucideR.drawable.lucide_ic_search
                            ),
                            stringResource(
                                if (searching) R.string.offline_close_search
                                else R.string.offline_search
                            ),
                        )
                    }
                }
                if (page != DownloadPage.SETTINGS && profile != null) {
                    IconButton(
                        onClick = {
                            settingsFrom = page
                            page = DownloadPage.SETTINGS
                        }
                    ) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_settings),
                            stringResource(R.string.offline_download_settings),
                        )
                    }
                }
            }
            if (
                connection.mode == ConnectionMode.OFFLINE ||
                    connection.reachability in
                        setOf(ServerReachability.UNREACHABLE, ServerReachability.CERTIFICATE_ERROR)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_wifi_off),
                        null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(
                            if (connection.mode == ConnectionMode.OFFLINE)
                                R.string.offline_mode_label
                            else R.string.offline_unreachable_label
                        ),
                        Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (connection.mode == ConnectionMode.OFFLINE)
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
            }
            if (
                connection.initialized &&
                    connection.mode == ConnectionMode.AUTOMATIC &&
                    profile != null &&
                    actions != null &&
                    !actions.canDownload(profile)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.offline_sign_in_manage),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOnline) { Text(stringResource(R.string.login)) }
                }
            }
            when {
                profileRows == null || !connection.initialized ->
                    Box(
                        Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                profile == null -> OfflineLibraryMessage(stringResource(R.string.offline_empty))
                profile.locked && page != DownloadPage.SETTINGS ->
                    OfflineLibraryMessage(stringResource(R.string.offline_locked))
                page == DownloadPage.LIBRARY && containers.isNotEmpty() && loadingParent ->
                    Box(
                        Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                page == DownloadPage.LIBRARY && containers.isNotEmpty() && parent == null ->
                    OfflineLibraryMessage(stringResource(R.string.offline_group_unavailable))
                else ->
                    savedState.SaveableStateProvider(
                        "${profile.id}:$page:${containers.lastOrNull()}:$playlistId"
                    ) {
                        when (page) {
                            DownloadPage.LIBRARY -> {
                                SavedDownloadLibrary(
                                    runtime,
                                    profile,
                                    parent,
                                    searching,
                                    pending,
                                    playlists.size,
                                    onManage = { page = DownloadPage.MANAGE },
                                    onPlaylists = { page = DownloadPage.PLAYLISTS },
                                    onPlayAlbum = { album ->
                                        scope.launch {
                                            val queue =
                                                offlineUiRead(emptyList()) { albumTracks(album) }
                                            queue.firstOrNull()?.let { first ->
                                                if (actions?.playSavedQueue != null)
                                                    actions.playSavedQueue.invoke(
                                                        profile,
                                                        first,
                                                        queue,
                                                    )
                                                else onItem(profile, first)
                                            }
                                        }
                                    },
                                    onItem = { item ->
                                        if (item.isOfflineContainer()) {
                                            previousSearches = previousSearches + searching
                                            searching = false
                                            containers = containers + item.id
                                        } else play(item)
                                    },
                                )
                                OfflineMusicMiniPlayer(profile) { onItem(profile, it) }
                            }
                            DownloadPage.PLAYLISTS ->
                                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                                    OfflinePlaylistsScreen(runtime, profile, onItem, playlistId) {
                                        playlistId = it
                                    }
                                }
                            DownloadPage.MANAGE -> TransferDownloadList(runtime, profile)
                            DownloadPage.SETTINGS -> DownloadSettings(runtime, profile, profiles)
                        }
                    }
            }
        }
    }
}

@Composable
internal fun OfflineLibraryMessage(message: String) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painterResource(LucideR.drawable.lucide_ic_download),
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.SavedDownloadLibrary(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    parent: MediaItem?,
    searching: Boolean,
    pending: Int,
    playlistCount: Int,
    onManage: () -> Unit,
    onPlaylists: () -> Unit,
    onPlayAlbum: (MediaItem) -> Unit,
    onItem: (MediaItem) -> Unit,
) {
    var libraryId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable(libraryId, query) { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf(emptyList<MediaItem>()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var canLoadMore by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var resume by remember { mutableStateOf(emptyList<MediaItem>()) }
    var nextUp by remember { mutableStateOf(emptyList<MediaItem>()) }
    val downloadedLibraries by
        produceState<List<OfflineDownloadLibrary>?>(null, profile.id) {
            runtime.catalog.downloadedLibraries(profile.id).collect { value = it }
        }
    val selectedLibrary = downloadedLibraries?.firstOrNull { it.id == libraryId }
    val revision by runtime.catalogRevision.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(searching) { if (!searching) query = "" }
    LaunchedEffect(downloadedLibraries, libraryId) {
        val libraries = downloadedLibraries
        val currentLibraryId = libraryId
        if (
            libraries != null &&
                currentLibraryId != null &&
                libraries.none { it.id == currentLibraryId }
        ) {
            libraryId = null
        }
    }
    LaunchedEffect(
        profile.id,
        parent?.id,
        libraryId,
        downloadedLibraries,
        query,
        page,
        revision,
        retry,
    ) {
        loading = true
        failed = false
        try {
            val loaded = mutableListOf<MediaItem>()
            for (index in 0..page) {
                val result =
                    runtime.catalog.browseDownloads(
                        profile.id,
                        libraryId,
                        query,
                        parent,
                        index * 100,
                    )
                loaded += result
                canLoadMore = result.size == 100
                if (!canLoadMore) break
            }
            rows = loaded.distinctBy { it.id }
            val showVideoSections = libraryId == null || selectedLibrary?.type == "tvshows"
            resume =
                if (parent == null && query.isBlank() && showVideoSections)
                    runtime.playbackState.continueWatching(profile.id).filter {
                        libraryId == null || it.libraryId == libraryId
                    }
                else emptyList()
            nextUp =
                if (parent == null && query.isBlank() && showVideoSections)
                    runtime.playbackState.nextUp(profile.id).filter {
                        libraryId == null || it.libraryId == libraryId
                    }
                else emptyList()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            failed = true
        } finally {
            loading = false
        }
    }
    if (searching)
        OutlinedTextField(
            query,
            {
                query = it
                scope.launch { listState.scrollToItem(0) }
            },
            label = { Text(stringResource(R.string.offline_search)) },
            singleLine = true,
            leadingIcon = { Icon(painterResource(LucideR.drawable.lucide_ic_search), null) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    if (parent == null)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = libraryId == null,
                    onClick = {
                        libraryId = null
                        scope.launch { listState.scrollToItem(0) }
                    },
                    label = { Text(stringResource(R.string.search_filter_all)) },
                )
            }
            items(downloadedLibraries.orEmpty(), key = { it.id }) { library ->
                FilterChip(
                    selected = libraryId == library.id,
                    onClick = {
                        libraryId = library.id
                        scope.launch { listState.scrollToItem(0) }
                    },
                    label = { Text(library.name) },
                )
            }
        }
    LazyColumn(
        Modifier.weight(1f),
        state = listState,
        contentPadding = PaddingValues(bottom = 20.dp),
    ) {
        if (parent == null && query.isBlank())
            item(key = "navigation") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OfflineNavigationAction(
                        stringResource(R.string.offline_manage_downloads),
                        pluralStringResource(R.plurals.offline_queue_count, pending, pending),
                        LucideR.drawable.lucide_ic_download,
                        Modifier.weight(1f),
                        onManage,
                    )
                    if (libraryId == null || selectedLibrary?.type == "music")
                        OfflineNavigationAction(
                            stringResource(R.string.offline_playlists),
                            pluralStringResource(
                                R.plurals.offline_playlist_count,
                                playlistCount,
                                playlistCount,
                            ),
                            LucideR.drawable.lucide_ic_list_music,
                            Modifier.weight(1f),
                            onPlaylists,
                        )
                }
            }
        if (resume.isNotEmpty()) {
            item(key = "resume-heading") {
                OfflineSectionHeading(stringResource(R.string.offline_continue_watching))
            }
            items(resume, key = { "resume:${it.id}" }) { item ->
                OfflineMediaRow(
                    runtime,
                    profile,
                    item,
                    revision,
                    onClick = { onItem(item) },
                    resume = true,
                )
            }
        }
        if (nextUp.isNotEmpty()) {
            item(key = "next-up-heading") {
                OfflineSectionHeading(stringResource(R.string.offline_next_up))
            }
            items(nextUp, key = { "next:${it.id}" }) { item ->
                OfflineMediaRow(
                    runtime,
                    profile,
                    item,
                    revision,
                    onClick = { onItem(item) },
                )
            }
        }
        if (parent != null)
            item(key = "group-summary") {
                val count by
                    produceState(OfflineAvailability(0, 0), parent.id, revision) {
                        value =
                            offlineUiRead(OfflineAvailability(0, 0)) {
                                runtime.catalog.availability(profile.id, parent.id)
                            }
                    }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        offlineAvailabilityLabel(parent, count),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (parent.type == "MusicAlbum")
                        FilledTonalButton(
                            enabled = rows.isNotEmpty(),
                            onClick = { onPlayAlbum(parent) },
                        ) {
                            Icon(
                                painterResource(LucideR.drawable.lucide_ic_play),
                                null,
                                Modifier.size(18.dp),
                            )
                            Text(stringResource(R.string.play_all), Modifier.padding(start = 8.dp))
                        }
                }
            }
        else if (rows.isNotEmpty())
            item(key = "library-heading") {
                OfflineSectionHeading(
                    stringResource(
                        if (query.isBlank()) R.string.offline_on_device else R.string.search
                    )
                )
            }
        if (loading && rows.isEmpty())
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        if (!loading && failed)
            item(key = "error") {
                Column(Modifier.padding(20.dp)) {
                    Text(stringResource(R.string.offline_request_failed))
                    TextButton(onClick = { retry++ }) { Text(stringResource(R.string.retry)) }
                }
            }
        if (!loading && !failed && rows.isEmpty())
            item(key = "empty") {
                OfflineLibraryMessage(
                    stringResource(
                        if (query.isNotBlank()) R.string.offline_no_matches
                        else R.string.offline_empty
                    )
                )
            }
        rows.forEachIndexed { index, item ->
            if (
                parent?.type == "Series" &&
                    (index == 0 || rows[index - 1].parentIndexNumber != item.parentIndexNumber)
            )
                item(key = "season:${item.parentIndexNumber}") {
                    OfflineSectionHeading(
                        stringResource(R.string.offline_season_number, item.parentIndexNumber ?: 0)
                    )
                }
            item(key = item.id) {
                OfflineMediaRow(
                    runtime,
                    profile,
                    item,
                    revision,
                    onClick = { onItem(item) },
                    withinGroup = parent != null,
                )
            }
        }
        if (canLoadMore)
            item(key = "load-more") {
                TextButton(
                    enabled = !loading,
                    onClick = { page++ },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.offline_load_more))
                }
            }
    }
}

@Composable
private fun OfflineNavigationAction(
    title: String,
    subtitle: String,
    icon: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier.clickable(role = Role.Button, onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            Modifier.padding(12.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(icon),
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun OfflineSectionHeading(title: String) {
    Text(
        title,
        Modifier.fillMaxWidth()
            .padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun OfflineMediaArtwork(
    runtime: OfflineRuntime,
    profileId: String,
    item: MediaItem,
    revision: Long,
) {
    var artwork by remember(profileId, item.id) { mutableStateOf<File?>(null) }
    LaunchedEffect(profileId, item.id, revision) {
        artwork =
            listOfNotNull(item.id, item.albumId, item.seriesId, item.parentId)
                .distinct()
                .firstNotNullOfOrNull {
                    offlineUiRead(null) { runtime.audio.artwork(profileId, it) }
                }
    }
    val size =
        if (item.isOfflineMusic()) Modifier.size(64.dp) else Modifier.width(72.dp).height(108.dp)
    Box(size.clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painterResource(
                        if (item.isOfflineMusic()) LucideR.drawable.lucide_ic_music
                        else LucideR.drawable.lucide_ic_film
                    ),
                    null,
                    Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        artwork?.let {
            AsyncImage(
                it,
                null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun offlineAvailabilityLabel(item: MediaItem, count: OfflineAvailability): String =
    when (item.type) {
        "Series",
        "Season" ->
            stringResource(R.string.offline_episode_availability, count.downloaded, count.total)
        "MusicAlbum",
        "MusicArtist" ->
            stringResource(R.string.offline_track_availability, count.downloaded, count.total)
        else -> stringResource(R.string.offline_transfer_progress, count.downloaded, count.total)
    }

@Composable
private fun OfflineMediaRow(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    item: MediaItem,
    revision: Long,
    onClick: () -> Unit,
    resume: Boolean = false,
    withinGroup: Boolean = false,
) {
    val group = item.isOfflineContainer()
    val count by
        produceState(OfflineAvailability(0, 0), item.id, revision) {
            value =
                offlineUiRead(OfflineAvailability(0, 0)) {
                    runtime.catalog.availability(profile.id, item.id)
                }
        }
    val context =
        when {
            item.type == "Episode" && withinGroup ->
                stringResource(
                    R.string.offline_episode_number,
                    item.parentIndexNumber ?: 0,
                    item.indexNumber ?: 0,
                )
            item.type == "Episode" -> episodeCardSubtitle(item)
            item.isOfflineMusic() -> musicAlbumArtist(item) ?: musicSubtitle(item)
            else -> itemSubtitle(item)
        }
    Row(
        Modifier.fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel =
                    stringResource(
                        if (group) R.string.open_details_description else R.string.play_description,
                        item.name,
                    ),
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OfflineMediaArtwork(runtime, profile.id, item, revision)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
            Text(
                if (item.type == "Episode" && !withinGroup) episodeCardTitle(item) else item.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (context.isNotBlank())
                Text(
                    context,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            if (group)
                Text(
                    offlineAvailabilityLabel(item, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            if (resume) {
                Text(
                    stringResource(R.string.offline_continue_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                progressPercent(item)?.let { percent ->
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
            if (count.newSourceAvailable > 0)
                Text(
                    stringResource(R.string.offline_new_version_available),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
        }
        if (group)
            Icon(
                painterResource(LucideR.drawable.lucide_ic_chevron_right),
                null,
                Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        else
            IconButton(onClick = onClick) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_play),
                    stringResource(R.string.play_description, item.name),
                    Modifier.size(20.dp),
                )
            }
        if (!resume) OfflineMediaActions(runtime, profile, item, revision)
    }
}

@Composable
private fun OfflineMediaActions(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    item: MediaItem,
    revision: Long,
) {
    var expanded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val watched by
        produceState(OfflineWatchedCounts(0, 0), item.id, revision) {
            if (item.type in setOf("Series", "Season"))
                value =
                    offlineUiRead(OfflineWatchedCounts(0, 0)) {
                        runtime.playbackState.downloadedEpisodeWatchCounts(profile.id, item.id)
                    }
        }
    fun perform(action: suspend () -> Unit) {
        expanded = false
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failed = true
            }
        }
    }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painterResource(LucideR.drawable.lucide_ic_ellipsis_vertical),
                stringResource(R.string.offline_actions_for, item.name),
            )
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (item.favorite) R.string.remove_favorite else R.string.add_favorite
                        )
                    )
                },
                leadingIcon = { Icon(painterResource(LucideR.drawable.lucide_ic_heart), null) },
                onClick = {
                    perform {
                        runtime.playbackState.setFavorite(profile.id, item.id, !item.favorite)
                    }
                },
            )
            if (item.type in setOf("Movie", "Series", "Episode", "MusicArtist"))
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (item.following == true) R.string.unfollow else R.string.follow
                            )
                        )
                    },
                    leadingIcon = {
                        Icon(painterResource(LucideR.drawable.lucide_ic_bookmark), null)
                    },
                    onClick = {
                        perform {
                            runtime.playbackState.setFollowing(
                                profile.id,
                                item.id,
                                item.following != true,
                            )
                        }
                    },
                )
            if (item.type in setOf("Movie", "Episode"))
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (item.played) R.string.mark_unwatched else R.string.mark_watched
                            )
                        )
                    },
                    onClick = {
                        perform {
                            runtime.playbackState.setPlayed(profile.id, item.id, !item.played)
                        }
                    },
                )
            if (watched.total > 0)
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (watched.watched < watched.total)
                                    R.string.offline_mark_downloaded_watched
                                else R.string.offline_mark_downloaded_unwatched
                            )
                        )
                    },
                    onClick = {
                        perform {
                            runtime.playbackState.markDownloadedEpisodes(
                                profile.id,
                                item.id,
                                watched.watched < watched.total,
                            )
                        }
                    },
                )
        }
    }
    if (failed)
        AlertDialog(
            onDismissRequest = { failed = false },
            text = { Text(stringResource(R.string.offline_request_failed)) },
            confirmButton = {
                TextButton(onClick = { failed = false }) { Text(stringResource(R.string.close)) }
            },
        )
}

package com.zenstream.zenstreammobile.offline

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ColumnScope.TransferDownloadList(runtime: OfflineRuntime, profile: OfflineProfile) {
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val selectionRows by
        remember(profile.id, offset) {
                runtime.database.offline().managedSelectionPage(profile.id, 21, offset)
            }
            .collectAsState(initial = null)
    val selections = selectionRows?.take(20)
    val listState = rememberLazyListState()
    val actions = LocalDownloadUi.current
    val connection by runtime.connection.state.collectAsState()
    val online =
        connection.mode == ConnectionMode.AUTOMATIC && actions?.canDownload?.invoke(profile) == true
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var failure by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<OfflineSelection?>(null) }
    fun perform(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
                failure = false
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failure = true
            }
        }
    }
    LazyColumn(
        Modifier.weight(1f),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "controls") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = online,
                    onClick = {
                        perform {
                            runtime.resumeDownloads(profile.id)
                            DownloadExecution.start(context, profile.id)
                        }
                    },
                ) {
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_play),
                        null,
                        Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.offline_resume_all),
                        Modifier.padding(start = 8.dp),
                    )
                }
                TextButton(
                    onClick = {
                        perform {
                            runtime.database.offline().pauseAllTransfers(profile.id)
                            runtime.database.offline().pauseEnumeration(profile.id)
                            DownloadExecution.stop(context)
                        }
                    }
                ) {
                    Text(stringResource(R.string.offline_pause_all))
                }
            }
        }
        if (failure)
            item {
                Text(
                    stringResource(R.string.offline_request_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        if (selections == null)
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }
        if (selections?.isEmpty() == true)
            item { OfflineLibraryMessage(stringResource(R.string.offline_no_transfers)) }
        items(selections.orEmpty(), key = { it.id }) { selection ->
            DownloadSelectionGroup(
                runtime,
                profile,
                selection,
                online,
                onRemove = { removing = selection },
                onFailure = { failure = true },
                onPageChange = {
                    scope.launch {
                        listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == selection.id }
                            ?.index
                            ?.let { listState.scrollToItem(it) }
                    }
                },
            )
        }
        if (offset > 0 || (selectionRows?.size ?: 0) > 20)
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (offset > 0)
                        TextButton(
                            onClick = {
                                offset = (offset - 20).coerceAtLeast(0)
                                scope.launch { listState.scrollToItem(0) }
                            }
                        ) {
                            Text(stringResource(R.string.offline_previous_selections))
                        }
                    if ((selectionRows?.size ?: 0) > 20)
                        TextButton(
                            onClick = {
                                offset += 20
                                scope.launch { listState.scrollToItem(0) }
                            }
                        ) {
                            Text(stringResource(R.string.offline_next_page))
                        }
                }
            }
    }
    removing?.let { selection ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.offline_remove_selection)) },
            text = { Text(stringResource(R.string.offline_remove_download_help, selection.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = null
                        perform { runtime.storage.removeSelection(profile.id, selection.id) }
                    }
                ) {
                    Text(stringResource(R.string.offline_remove_selection))
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun DownloadSelectionGroup(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    selection: OfflineSelection,
    online: Boolean,
    onRemove: () -> Unit,
    onFailure: () -> Unit,
    onPageChange: () -> Unit,
) {
    var expanded by
        rememberSaveable(selection.id) {
            mutableStateOf(selection.state !in setOf("complete", "metadata_complete"))
        }
    var menu by remember { mutableStateOf(false) }
    var offset by rememberSaveable(selection.id) { mutableIntStateOf(0) }
    val transferRows by
        remember(profile.id, selection.id, offset) {
                runtime.database
                    .offline()
                    .selectionTransferPage(profile.id, selection.id, 21, offset)
            }
            .collectAsState(initial = null)
    val totals by
        remember(profile.id, selection.id) {
                runtime.database.offline().selectionTotals(profile.id, selection.id)
            }
            .collectAsState(initial = DownloadTotals(0, 0, 0, 0))
    val transfers = transferRows?.take(20)
    val actions = LocalDownloadUi.current
    val expansionState =
        stringResource(if (expanded) R.string.offline_expanded else R.string.offline_collapsed)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = expansionState }
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(
                        if (expanded) LucideR.drawable.lucide_ic_chevron_down
                        else LucideR.drawable.lucide_ic_chevron_right
                    ),
                    null,
                    Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(selection.title, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            when {
                                selection.error != null -> R.string.offline_failed
                                selection.state.startsWith("paused_") -> R.string.offline_paused
                                selection.state == "metadata_complete" ->
                                    R.string.offline_metadata_saved
                                selection.state in
                                    setOf("enumerating", "ready", "queueing", "metadata_only") ->
                                    R.string.offline_preparing
                                else -> R.string.offline_transfer_progress
                            },
                            totals.complete,
                            selection.itemCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_ellipsis_vertical),
                            stringResource(R.string.offline_actions_for, selection.title),
                        )
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.offline_download_missing)) },
                            enabled =
                                online && actions != null && selection.kind != "playlist_metadata",
                            onClick = {
                                menu = false
                                actions?.downloadMissing?.invoke(selection)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.offline_remove_selection)) },
                            onClick = {
                                menu = false
                                onRemove()
                            },
                        )
                    }
                }
            }
            if (expanded) {
                if (transfers == null)
                    Box(
                        Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                    }
                transfers.orEmpty().forEach { transfer ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .3f))
                    DownloadTransferRow(runtime, profile, transfer, online, onFailure)
                }
                if (offset > 0 || (transferRows?.size ?: 0) > 20)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        if (offset > 0)
                            TextButton(
                                onClick = {
                                    offset = (offset - 20).coerceAtLeast(0)
                                    onPageChange()
                                }
                            ) {
                                Text(stringResource(R.string.offline_previous_page))
                            }
                        if ((transferRows?.size ?: 0) > 20)
                            TextButton(
                                onClick = {
                                    offset += 20
                                    onPageChange()
                                }
                            ) {
                                Text(stringResource(R.string.offline_next_page))
                            }
                    }
            }
        }
    }
}

@Composable
private fun DownloadTransferRow(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    transfer: OfflineTransfer,
    online: Boolean,
    onFailure: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val item by
        produceState<MediaItem?>(null, profile.id, transfer.item) {
            value = offlineUiRead(null) { runtime.catalog.item(profile.id, transfer.item) }
        }
    fun perform(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                onFailure()
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    item?.name ?: stringResource(R.string.offline_unknown_item),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    transferStateLabel(transfer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (transfer.state) {
                "complete" ->
                    Icon(
                        painterResource(LucideR.drawable.lucide_ic_check),
                        null,
                        Modifier.padding(12.dp).size(20.dp),
                    )
                "paused",
                "failed",
                "cancelled" ->
                    TextButton(
                        enabled = online,
                        onClick = {
                            perform {
                                runtime.downloads.retry(profile.id, transfer.id)
                                DownloadExecution.start(context, profile.id)
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (transfer.state == "paused") R.string.offline_resume
                                else R.string.retry
                            )
                        )
                    }
                else ->
                    TextButton(
                        onClick = { perform { runtime.downloads.pause(profile.id, transfer.id) } }
                    ) {
                        Text(stringResource(R.string.offline_pause))
                    }
            }
            if (
                transfer.state !in setOf("complete", "cancelled") ||
                    (transfer.state == "complete" && item?.type == "Episode")
            )
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_ellipsis_vertical),
                            stringResource(
                                R.string.offline_actions_for,
                                item?.name ?: stringResource(R.string.offline_unknown_item),
                            ),
                        )
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        if (transfer.state == "complete")
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(R.string.offline_remove_downloaded_item))
                                },
                                leadingIcon = {
                                    Icon(painterResource(LucideR.drawable.lucide_ic_trash_2), null)
                                },
                                onClick = {
                                    menu = false
                                    removing = true
                                },
                            )
                        else
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cancel)) },
                                onClick = {
                                    menu = false
                                    perform { runtime.downloads.cancel(profile.id, transfer.id) }
                                },
                            )
                    }
                }
        }
        if (transfer.total > 0)
            LinearProgressIndicator(
                progress = {
                    (transfer.bytes.toDouble() / transfer.total).toFloat().coerceIn(0f, 1f)
                },
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp, bottom = 6.dp),
            )
        Text(
            Formatter.formatFileSize(context, transfer.bytes) +
                " / " +
                if (transfer.total > 0) Formatter.formatFileSize(context, transfer.total)
                else stringResource(R.string.offline_size_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (removing)
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text(stringResource(R.string.offline_remove_downloaded_item)) },
            text = {
                Text(
                    stringResource(
                        R.string.offline_remove_download_help,
                        item?.name ?: stringResource(R.string.offline_unknown_item),
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        removing = false
                        perform {
                            runtime.downloads.removeDownloadedItem(
                                profile.id,
                                transfer.selection,
                                transfer.item,
                            )
                        }
                    }
                ) {
                    Text(stringResource(R.string.offline_remove_downloaded_item))
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
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

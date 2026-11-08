package com.zenstream.zenstreammobile.offline

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zenstream.zenstreammobile.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun UpdateDownloadAction(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    item: String,
    title: String,
    container: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connection by runtime.connection.state.collectAsState()
    val online =
        connection.mode == ConnectionMode.AUTOMATIC &&
            LocalDownloadUi.current?.canDownload?.invoke(profile) == true
    var open by remember(item) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var previews by remember { mutableStateOf<List<MediaUpdatePreview>?>(null) }
    var chosenSource by remember { mutableStateOf<JSONObject?>(null) }
    var quality by remember { mutableStateOf("original") }
    TextButton(
        enabled = online && !busy,
        onClick = {
            open = true
            busy = true
            failed = false
            previews = null
            scope.launch {
                try {
                    val session = requireNotNull(runtime.sessionStore.session.first())
                    val found = mutableListOf<MediaUpdatePreview>()
                    var after = ""
                    while (true) {
                        val items =
                            runtime.database
                                .offline()
                                .downloadedItemsUnder(profile.id, item, after, 100)
                        if (items.isEmpty()) break
                        for (id in items) {
                            val asset =
                                runtime.database.offline().latestCompleteAsset(profile.id, id)
                                    ?: continue
                            if (
                                runtime.database
                                    .offline()
                                    .item(profile.id, id)
                                    ?.newSourceAvailable != true
                            )
                                continue
                            found += runtime.mediaUpdates.preview(session, profile.id, asset.id)
                        }
                        after = items.last()
                    }
                    previews = found
                    chosenSource = found.singleOrNull()?.source
                    quality = found.singleOrNull()?.asset?.quality ?: "original"
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    failed = true
                } finally {
                    busy = false
                }
            }
        },
    ) {
        Text(
            stringResource(
                if (container) R.string.offline_update_downloaded_items
                else R.string.offline_update_download
            )
        )
    }
    if (open)
        AlertDialog(
            onDismissRequest = { if (!busy) open = false },
            title = { Text(title) },
            text = {
                Column(
                    Modifier.fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(24.dp))
                    Text(stringResource(R.string.offline_update_download_help))
                    if (failed)
                        Text(
                            stringResource(R.string.offline_request_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                    val single = previews?.singleOrNull()
                    if (single != null) {
                        if (single.source == null)
                            Text(stringResource(R.string.offline_update_choose_source))
                        single.sources.forEachIndexed { index, source ->
                            Row {
                                RadioButton(
                                    selected =
                                        chosenSource?.optString("id") == source.optString("id"),
                                    onClick = { chosenSource = source },
                                )
                                Text(
                                    stringResource(
                                        R.string.offline_update_source,
                                        index + 1,
                                        source.optString("videoCodec").ifBlank {
                                            source.optString("audioCodec")
                                        },
                                        source
                                            .optInt("height")
                                            .takeIf { it > 0 }
                                            ?.let { "${it}p" }
                                            .orEmpty(),
                                    )
                                )
                            }
                        }
                        single.availableProfiles(chosenSource).forEach { value ->
                            Row {
                                RadioButton(
                                    selected = quality == value,
                                    onClick = { quality = value },
                                )
                                Text(qualityLabel(value))
                            }
                        }
                        estimateDownloadBytes(quality, chosenSource)?.let { bytes ->
                            Text(
                                stringResource(
                                    R.string.offline_update_estimated_size,
                                    Formatter.formatFileSize(context, bytes),
                                )
                            )
                        } ?: Text(stringResource(R.string.offline_update_size_pending))
                    } else if (previews != null) {
                        val eligible =
                            previews!!.filter {
                                it.source != null &&
                                    it.asset.quality in it.availableProfiles(it.source)
                            }
                        val sizes = eligible.mapNotNull {
                            estimateDownloadBytes(it.asset.quality, it.source)
                        }
                        if (eligible.isNotEmpty() && sizes.size == eligible.size) {
                            Text(
                                stringResource(
                                    R.string.offline_update_estimated_size,
                                    Formatter.formatFileSize(context, sizes.sum()),
                                )
                            )
                        } else Text(stringResource(R.string.offline_update_size_pending))
                        Text(
                            stringResource(
                                R.string.offline_update_count,
                                previews!!.count {
                                    it.source != null &&
                                        it.asset.quality in it.availableProfiles(it.source)
                                },
                            )
                        )
                        Text(stringResource(R.string.offline_update_container_help))
                    }
                }
            },
            confirmButton = {
                val eligible =
                    previews?.let { list ->
                        if (list.size == 1)
                            chosenSource != null &&
                                quality in list[0].availableProfiles(chosenSource)
                        else
                            list.any {
                                it.source != null &&
                                    it.asset.quality in it.availableProfiles(it.source)
                            }
                    } == true
                TextButton(
                    enabled = !busy && eligible,
                    onClick = {
                        busy = true
                        failed = false
                        scope.launch {
                            try {
                                val session = requireNotNull(runtime.sessionStore.session.first())
                                val list = previews.orEmpty()
                                for (preview in list) {
                                    val source =
                                        if (list.size == 1) chosenSource else preview.source
                                    val selectedQuality =
                                        if (list.size == 1) quality else preview.asset.quality
                                    if (
                                        source != null &&
                                            selectedQuality in preview.availableProfiles(source)
                                    )
                                        runtime.mediaUpdates.enqueue(
                                            session,
                                            preview,
                                            source,
                                            selectedQuality,
                                            false,
                                        )
                                }
                                DownloadExecution.start(context, profile.id)
                                open = false
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                failed = true
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(R.string.offline_update_download))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { open = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
}

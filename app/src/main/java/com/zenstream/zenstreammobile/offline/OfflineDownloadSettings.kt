package com.zenstream.zenstreammobile.offline

import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import kotlinx.coroutines.launch

@Composable
internal fun DownloadSettings(
    runtime: OfflineRuntime,
    profile: OfflineProfile,
    profiles: List<OfflineProfile>,
) {
    val used by runtime.database.offline().storageUsed(profile.id).collectAsState(initial = 0L)
    val connection by runtime.connection.state.collectAsState()
    val metered by runtime.connection.allowMetered.collectAsState()
    val limit by runtime.connection.downloadLimitBytes.collectAsState()
    var text by
        remember(limit) {
            mutableStateOf(limit?.toDouble()?.div(OFFLINE_FREE_RESERVE)?.toString().orEmpty())
        }
    var invalid by remember { mutableStateOf(false) }
    var profileMenu by remember { mutableStateOf(false) }
    val actions = LocalDownloadUi.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val offlineLabel = stringResource(R.string.offline_use_mode)
    val meteredLabel = stringResource(R.string.offline_allow_metered)
    val noLimitLabel = stringResource(R.string.offline_no_limit)
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (profiles.size > 1)
            Box {
                OutlinedButton(
                    onClick = { profileMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(profile.displayName, Modifier.weight(1f))
                    Icon(painterResource(LucideR.drawable.lucide_ic_chevron_down), null)
                }
                DropdownMenu(profileMenu, onDismissRequest = { profileMenu = false }) {
                    profiles.forEach { saved ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    saved.displayName +
                                        " · " +
                                        android.net.Uri.parse(saved.serverUrl).host.orEmpty()
                                )
                            },
                            onClick = {
                                profileMenu = false
                                scope.launch { runtime.connection.selectProfile(saved.id) }
                            },
                        )
                    }
                }
            }
        Text(
            stringResource(R.string.offline_connection_settings),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(offlineLabel, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.offline_mode_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        connection.mode == ConnectionMode.OFFLINE,
                        modifier = Modifier.semantics { contentDescription = offlineLabel },
                        onCheckedChange = { enabled ->
                            scope.launch {
                                runtime.connection.setMode(
                                    if (enabled) ConnectionMode.OFFLINE
                                    else ConnectionMode.AUTOMATIC
                                )
                            }
                        },
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        meteredLabel,
                        Modifier.weight(1f).padding(end = 12.dp),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Switch(
                        metered,
                        modifier = Modifier.semantics { contentDescription = meteredLabel },
                        onCheckedChange = {
                            scope.launch { runtime.connection.setAllowMetered(it) }
                        },
                    )
                }
            }
        }
        Text(
            stringResource(R.string.offline_storage_settings),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(
                        R.string.offline_storage_used,
                        Formatter.formatFileSize(context, used),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.offline_no_limit),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(
                        limit == null,
                        modifier = Modifier.semantics { contentDescription = noLimitLabel },
                        onCheckedChange = { unlimited ->
                            scope.launch {
                                runtime.connection.setDownloadLimit(
                                    if (unlimited) null else 10L * OFFLINE_FREE_RESERVE
                                )
                            }
                        },
                    )
                }
                if (limit != null) {
                    OutlinedTextField(
                        text,
                        {
                            text = it
                            invalid = false
                        },
                        label = { Text(stringResource(R.string.offline_limit_size)) },
                        supportingText = {
                            if (invalid) Text(stringResource(R.string.offline_limit_invalid))
                        },
                        isError = invalid,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val value = text.toDoubleOrNull()
                            if (value == null || !value.isFinite() || value <= 0 || value > 1048576)
                                invalid = true
                            else
                                scope.launch {
                                    runtime.connection.setDownloadLimit(
                                        (value * OFFLINE_FREE_RESERVE).toLong()
                                    )
                                }
                        }
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
                Text(
                    stringResource(R.string.offline_storage_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(
            enabled =
                connection.mode == ConnectionMode.AUTOMATIC &&
                    actions?.canDownload?.invoke(profile) == true,
            onClick = { scope.launch { runtime.refreshMetadata(profile.id) } },
        ) {
            Icon(painterResource(LucideR.drawable.lucide_ic_refresh_cw), null, Modifier.size(18.dp))
            Text(stringResource(R.string.offline_refresh_metadata), Modifier.padding(start = 8.dp))
        }
    }
}

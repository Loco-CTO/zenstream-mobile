package com.zenstream.zenstreammobile.offline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.MediaItem
import kotlinx.coroutines.launch

@Composable
fun OfflineEntryScreen(
    runtime: OfflineRuntime,
    onOnline: () -> Unit,
    onItem: (OfflineProfile, MediaItem) -> Unit = { _, _ -> },
) {
    val profiles by runtime.database.offline().profiles().collectAsState(initial = emptyList())
    val connection by runtime.connection.state.collectAsState()
    val scope = rememberCoroutineScope()
    val profile =
        profiles.firstOrNull { it.id == connection.profile } ?: profiles.firstOrNull { !it.locked }
    var category by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf(emptyList<MediaItem>()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(profile?.id, category, query) {
        found = emptyList()
        error = null
        if (profile != null && !profile.locked) {
            runCatching { runtime.catalog.search(profile.id, category, query) }
                .onSuccess { found = it }
                .onFailure { error = it.message }
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.offline_downloads),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Text(
            stringResource(
                if (connection.mode == ConnectionMode.OFFLINE) R.string.offline_mode_active
                else R.string.offline_local_available
            )
        )
        Row {
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
            if (connection.mode != ConnectionMode.OFFLINE) {
                TextButton(
                    onClick = {
                        scope.launch { runtime.connection.setMode(ConnectionMode.OFFLINE) }
                    }
                ) {
                    Text(stringResource(R.string.offline_use_mode))
                }
            }
        }
        if (profiles.size > 1) {
            profiles.forEach { candidate ->
                TextButton(
                    onClick = { scope.launch { runtime.connection.selectProfile(candidate.id) } }
                ) {
                    Text(
                        candidate.displayName +
                            if (candidate.locked) " · " + stringResource(R.string.offline_locked)
                            else ""
                    )
                }
            }
        }
        when {
            profile == null -> Text(stringResource(R.string.offline_empty))
            profile.locked -> Text(stringResource(R.string.offline_locked))
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                            "movies" to R.string.offline_movies,
                            "tv" to R.string.offline_tv,
                            "music" to R.string.offline_music,
                        )
                        .forEach { (key, label) ->
                            FilterChip(
                                selected = category == key,
                                onClick = { category = if (category == key) "" else key },
                                label = { Text(stringResource(label)) },
                            )
                        }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.offline_search)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (found.isEmpty())
                    Text(
                        stringResource(R.string.offline_empty),
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                LazyColumn(Modifier.weight(1f)) {
                    items(found, key = { it.id }) { item ->
                        ListItem(
                            headlineContent = { Text(item.name) },
                            supportingContent = { Text(item.type.orEmpty()) },
                            modifier = Modifier.clickable { onItem(profile, item) },
                        )
                    }
                }
            }
        }
    }
}

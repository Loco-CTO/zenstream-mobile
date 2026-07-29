package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.SyncplayManager
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.SyncplayGroup
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun SyncplayGroupMenu(
    manager: SyncplayManager,
    session: AuthSession,
    onReturnToView: (SyncplayGroup) -> Unit,
) {
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }, enabled = state.participantId.isNotBlank()) {
        Text(stringResource(R.string.syncplay_groups), style = MaterialTheme.typography.labelLarge)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        Column(modifier = Modifier.widthIn(min = 280.dp, max = 360.dp).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.syncplay_groups), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Button(onClick = { scope.launch { manager.create() } }, enabled = state.active == null) { Text(stringResource(R.string.syncplay_create)) }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
            val active = state.active
            if (active != null) {
                ActiveGroupContent(active, manager, session, onReturnToView)
            } else if (state.groups.isEmpty()) {
                Text(stringResource(R.string.syncplay_empty), modifier = Modifier.padding(vertical = 20.dp))
            } else {
                LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                    items(state.groups, key = { it.id }) { group ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(group.name)
                                Text(stringResource(R.string.syncplay_member_count, group.members.size), style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(onClick = { scope.launch { manager.join(group.id) } }) { Text(stringResource(R.string.syncplay_join)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveGroupContent(
    group: SyncplayGroup,
    manager: SyncplayManager,
    session: AuthSession,
    onReturnToView: (SyncplayGroup) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
        Text(group.name, style = MaterialTheme.typography.titleSmall)
        group.members.forEach { member ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(member.username + if (member.userId == group.hostUserId) " • ${stringResource(R.string.syncplay_host)}" else "", modifier = Modifier.weight(1f))
                if (group.hostUserId == session.userId && member.userId != session.userId) {
                    OutlinedButton(onClick = { scope.launch { manager.removeMember(member.userId) } }) { Text(stringResource(R.string.syncplay_remove)) }
                }
            }
        }
        if (group.hostUserId == session.userId) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = group.allowViewerControls, onCheckedChange = { value -> scope.launch { manager.setControls(value) } })
                Text(stringResource(R.string.syncplay_allow_controls))
            }
        }
        if (group.itemId != null) {
            Button(onClick = { scope.launch { manager.setWatchingTogether(true); onReturnToView(group) } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.syncplay_return_to_view)) }
        }
        OutlinedButton(onClick = { scope.launch { manager.leave() } }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.syncplay_leave)) }
    }
}

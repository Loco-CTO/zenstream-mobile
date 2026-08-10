package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.SyncplayManager
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.SyncplayGroup
import com.zenstream.zenstreammobile.model.SyncplayMember
import com.zenstream.zenstreammobile.model.SyncplayUiState
import com.zenstream.zenstreammobile.model.mediaItemId
import kotlinx.coroutines.launch

private val SyncplayPlayerSheetSurface = Color(0xFF1B1B1F)

@Composable
fun SyncplayGroupMenu(
    manager: SyncplayManager,
    session: AuthSession,
    onReturnToView: (SyncplayGroup) -> Unit,
    playerContext: Boolean = false,
) {
    val state by manager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }

    SyncplayGroupButton(
        enabled = state.participantId.isNotBlank(),
        playerContext = playerContext,
        onClick = {
            expanded = true
            scope.launch { runCatching { manager.refresh() } }
        },
    )

    if (expanded) {
        SyncplayGroupSheet(
            state = state,
            userId = session.userId,
            playerContext = playerContext,
            onDismiss = { expanded = false },
            onCreate = { scope.launch { manager.create() } },
            onJoin = { groupId ->
                scope.launch { manager.join(groupId) }
            },
            onRemoveMember = { memberId -> scope.launch { manager.removeMember(memberId) } },
            onControlsChanged = { enabled -> scope.launch { manager.setControls(enabled) } },
            onReturnToView = { group ->
                scope.launch {
                    manager.setWatchingTogether(true)
                    expanded = false
                    onReturnToView(group)
                }
            },
            onLeave = { scope.launch { manager.leave() } },
        )
    }
}

@Composable
internal fun SyncplayGroupButton(
    enabled: Boolean,
    playerContext: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_users),
            contentDescription = stringResource(R.string.syncplay_groups),
            tint =
                when {
                    !enabled && playerContext -> Color.White.copy(alpha = .3f)
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)
                    playerContext -> Color.White
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncplayGroupSheet(
    state: SyncplayUiState,
    userId: String,
    playerContext: Boolean,
    onDismiss: () -> Unit,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onControlsChanged: (Boolean) -> Unit,
    onReturnToView: (SyncplayGroup) -> Unit,
    onLeave: () -> Unit,
) {
    val surfaceColor =
        if (playerContext) SyncplayPlayerSheetSurface else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (playerContext) Color.White else MaterialTheme.colorScheme.onSurface

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = surfaceColor,
        contentColor = contentColor,
        scrimColor = Color.Black.copy(alpha = if (playerContext) .72f else .52f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = contentColor.copy(alpha = .36f))
        },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.active?.let { group ->
                    item {
                        ActiveGroupContent(
                            group = group,
                            userId = userId,
                            onRemoveMember = onRemoveMember,
                            onControlsChanged = onControlsChanged,
                            onReturnToView = onReturnToView,
                            onLeave = onLeave,
                        )
                    }
                }
                    ?: item {
                        GroupBrowserContent(
                            state = state,
                            onCreate = onCreate,
                            onJoin = onJoin,
                        )
                    }
            }
        }
    }
}

@Composable
private fun GroupBrowserContent(
    state: SyncplayUiState,
    onCreate: () -> Unit,
    onJoin: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SyncplayIconBadge()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.syncplay_groups),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.syncplay_group_count,
                            state.groups.size,
                            state.groups.size,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onCreate) {
                Text(stringResource(R.string.syncplay_create))
            }
        }

        state.error?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (state.groups.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SyncplayIconBadge(large = true)
                Text(
                    text = stringResource(R.string.syncplay_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.groups.forEach { group ->
                    GroupRow(group = group, onJoin = onJoin)
                }
            }
        }
    }
}

@Composable
private fun GroupRow(group: SyncplayGroup, onJoin: (String) -> Unit) {
    Surface(
        modifier =
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable {
                onJoin(group.id)
            },
        color = MaterialTheme.colorScheme.surface.copy(alpha = .68f),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SyncplayIconBadge()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.syncplay_member_count, group.members.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { onJoin(group.id) }) {
                Text(stringResource(R.string.syncplay_join))
            }
        }
    }
}

@Composable
private fun ActiveGroupContent(
    group: SyncplayGroup,
    userId: String,
    onRemoveMember: (String) -> Unit,
    onControlsChanged: (Boolean) -> Unit,
    onReturnToView: (SyncplayGroup) -> Unit,
    onLeave: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SyncplayIconBadge()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.syncplay_member_count, group.members.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = .68f),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                group.members.forEach { member ->
                    GroupMemberRow(
                        member = member,
                        hostUserId = group.hostUserId,
                        canRemove = group.hostUserId == userId && member.userId != userId,
                        onRemove = { onRemoveMember(member.userId) },
                    )
                }
            }
        }

        if (group.hostUserId == userId) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = group.allowViewerControls,
                    onCheckedChange = onControlsChanged,
                )
                Text(
                    text = stringResource(R.string.syncplay_allow_controls),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (group.mediaItemId() != null) {
            Button(
                onClick = { onReturnToView(group) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.syncplay_return_to_view))
            }
        }
        OutlinedButton(
            onClick = onLeave,
            modifier = Modifier.fillMaxWidth(),
            colors =
                ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .55f)),
        ) {
            Text(stringResource(R.string.syncplay_leave))
        }
    }
}

@Composable
private fun GroupMemberRow(
    member: SyncplayMember,
    hostUserId: String,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier.size(32.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = member.username.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = member.username,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (member.userId == hostUserId) {
                Text(
                    text = stringResource(R.string.syncplay_host),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (canRemove) {
            OutlinedButton(onClick = onRemove) {
                Text(stringResource(R.string.syncplay_remove))
            }
        }
    }
}

@Composable
private fun SyncplayIconBadge(large: Boolean = false) {
    val size = if (large) 48.dp else 36.dp
    Box(
        modifier =
            Modifier.size(size)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = .16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_users),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(if (large) 24.dp else 19.dp),
        )
    }
}

package com.zenstream.zenstreammobile.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.ui.components.UserAvatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPageScreen(
    repository: CatalogRepository,
    session: AuthSession,
    onLogout: () -> Unit,
    onPickAvatar: () -> Unit = {},
    avatarPickerResult: Uri? = null,
    onAvatarPickerResultConsumed: () -> Unit = {},
) {
    var avatarActionsOpen by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleteConfirmationOpen by remember { mutableStateOf(false) }
    var removingAvatar by remember { mutableStateOf(false) }
    var avatarError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val removeFailed = stringResource(R.string.avatar_remove_failed)

    LaunchedEffect(avatarPickerResult) {
        if (avatarPickerResult != null) {
            editorOpen = true
        }
    }

    SettingsScreen(
        repository = repository,
        onBack = {},
        onLogout = onLogout,
        rootTitle = R.string.my_page,
        showRootNavigation = false,
        showSettingsHeading = true,
        rootHeader = {
            ProfileCard(
                session = session,
                onEditAvatar = { avatarActionsOpen = true },
                avatarError = avatarError,
            )
        },
    )

    if (avatarActionsOpen) {
        AvatarActionSheet(
            hasAvatar = session.avatarVersion != null,
            onDismiss = { avatarActionsOpen = false },
            onUpload = {
                scope.launch {
                    avatarActionsOpen = false
                    onPickAvatar()
                }
            },
            onDelete = {
                scope.launch {
                    avatarActionsOpen = false
                    deleteConfirmationOpen = true
                }
            },
        )
    }

    if (deleteConfirmationOpen) {
        AvatarDeleteConfirmationDialog(
            deleting = removingAvatar,
            onDismiss = { deleteConfirmationOpen = false },
            onConfirm = {
                if (session.avatarVersion != null) {
                    removingAvatar = true
                    avatarError = null
                    scope.launch {
                        runCatching { repository.removeAvatar(session) }
                            .onSuccess { deleteConfirmationOpen = false }
                            .onFailure {
                                avatarError = removeFailed
                                deleteConfirmationOpen = false
                            }
                        removingAvatar = false
                    }
                } else {
                    deleteConfirmationOpen = false
                }
            },
        )
    }

    if (editorOpen) {
        AvatarEditorDialog(
            session = session,
            repository = repository,
            onSessionChanged = {},
            onDismiss = { editorOpen = false },
            pickedUri = avatarPickerResult,
            onPickImage = onPickAvatar,
            onPickedImageConsumed = onAvatarPickerResultConsumed,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AvatarActionSheet(
    hasAvatar: Boolean,
    onDismiss: () -> Unit,
    onUpload: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetSurface = Color(0xFF151518)
    val actionSurface = Color(0xFF1D1D21)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = sheetSurface,
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = .72f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = .32f))
        },
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.avatar_actions_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = actionSurface,
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    AvatarActionRow(
                        icon = LucideR.drawable.lucide_ic_image,
                        label = stringResource(R.string.avatar_upload_image),
                        onClick = onUpload,
                    )
                    if (hasAvatar) {
                        HorizontalDivider(color = Color.White.copy(alpha = .12f))
                        AvatarActionRow(
                            icon = LucideR.drawable.lucide_ic_trash_2,
                            label = stringResource(R.string.avatar_delete),
                            contentColor = MaterialTheme.colorScheme.error,
                            onClick = onDelete,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AvatarDeleteConfirmationDialog(
    deleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(stringResource(R.string.avatar_delete_confirmation_title)) },
        text = { Text(stringResource(R.string.avatar_delete_confirmation_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deleting) {
                if (deleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.avatar_delete))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !deleting) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun AvatarActionRow(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    contentColor: Color = Color.White,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 68.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        androidx.compose.material3.Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = contentColor.copy(alpha = .84f),
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
internal fun ProfileCard(
    session: AuthSession,
    onEditAvatar: () -> Unit,
    avatarError: String? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ColorProfileCard),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            UserAvatar(
                session = session,
                userId = session.userId,
                username = session.username,
                modifier = Modifier.size(88.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.profile),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = session.username,
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onEditAvatar) {
                    Text(
                        stringResource(
                            if (session.avatarVersion == null) R.string.add_avatar
                            else R.string.change_avatar
                        )
                    )
                }
                avatarError?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private val ColorProfileCard = androidx.compose.ui.graphics.Color(0xFF111111)

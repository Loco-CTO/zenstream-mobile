package com.zenstream.zenstreammobile.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.ui.components.UserAvatar
import kotlinx.coroutines.launch

@Composable
fun MyPageScreen(
    repository: CatalogRepository,
    session: AuthSession,
    onLogout: () -> Unit,
    onPickAvatar: () -> Unit = {},
    avatarPickerResult: Uri? = null,
    onAvatarPickerResultConsumed: () -> Unit = {},
) {
    var editorOpen by remember { mutableStateOf(false) }
    var removingAvatar by remember { mutableStateOf(false) }
    var avatarError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val removeFailed = stringResource(R.string.avatar_remove_failed)
    val removeAvatar: () -> Unit = {
        if (!removingAvatar && session.avatarVersion != null) {
            removingAvatar = true
            avatarError = null
            scope.launch {
                runCatching { repository.removeAvatar(session) }
                    .onFailure { avatarError = removeFailed }
                removingAvatar = false
            }
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
                onEditAvatar = { editorOpen = true },
                onRemoveAvatar = removeAvatar,
                removingAvatar = removingAvatar,
                avatarError = avatarError,
            )
        },
    )
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

@Composable
internal fun ProfileCard(
    session: AuthSession,
    onEditAvatar: () -> Unit,
    onRemoveAvatar: () -> Unit = {},
    removingAvatar: Boolean = false,
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
                if (session.avatarVersion != null) {
                    TextButton(
                        onClick = onRemoveAvatar,
                        enabled = !removingAvatar,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                    ) {
                        if (removingAvatar) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        } else {
                            Text(stringResource(R.string.remove_avatar))
                        }
                    }
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

package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.ui.components.UserAvatar

@Composable
fun MyPageScreen(
    repository: CatalogRepository,
    session: AuthSession,
    onLogout: () -> Unit,
) {
    var editorOpen by remember { mutableStateOf(false) }
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
            )
        },
    )
    if (editorOpen) {
        AvatarEditorDialog(
            session = session,
            repository = repository,
            onSessionChanged = {},
            onDismiss = { editorOpen = false },
        )
    }
}

@Composable
internal fun ProfileCard(session: AuthSession, onEditAvatar: () -> Unit) {
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
            }
        }
    }
}

private val ColorProfileCard = androidx.compose.ui.graphics.Color(0xFF111111)

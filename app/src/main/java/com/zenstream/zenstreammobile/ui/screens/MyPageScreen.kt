package com.zenstream.zenstreammobile.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.ui.SettingsViewModel
import com.zenstream.zenstreammobile.ui.components.UserAvatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyPageScreen(
    repository: CatalogRepository,
    session: AuthSession,
    onLogout: () -> Unit,
    outerPadding: PaddingValues = PaddingValues(),
    onPickAvatar: () -> Unit = {},
    avatarPickerResult: Uri? = null,
    onAvatarPickerResultConsumed: () -> Unit = {},
    onPasswordChanged: () -> Unit = {},
) {
    val settingsViewModel: SettingsViewModel =
        viewModel(
            key = "settings",
            factory = SettingsViewModel.Factory(repository),
        )
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    var avatarActionsOpen by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleteConfirmationOpen by remember { mutableStateOf(false) }
    var removingAvatar by remember { mutableStateOf(false) }
    var avatarError by remember { mutableStateOf<String?>(null) }
    var settingsSection by remember { mutableStateOf<MyPageSettingsSection?>(null) }
    var passwordEditorOpen by remember { mutableStateOf(false) }
    var passwordChangeSucceeded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val removeFailed = stringResource(R.string.avatar_remove_failed)

    BackHandler(enabled = settingsSection != null || passwordEditorOpen) {
        if (passwordEditorOpen) {
            if (passwordChangeSucceeded) {
                onPasswordChanged()
            } else {
                passwordEditorOpen = false
            }
        } else {
            settingsSection = null
        }
    }

    LaunchedEffect(avatarPickerResult) {
        if (avatarPickerResult != null) {
            editorOpen = true
        }
    }

    PullToRefreshLayout(
        isRefreshing = settingsState.refreshing,
        onRefresh = settingsViewModel::refresh,
        modifier = Modifier.padding(outerPadding),
    ) {
        val activeSection = settingsSection
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding =
                PaddingValues(
                    start = 16.dp,
                    top = if (activeSection == null && !passwordEditorOpen) 20.dp else 8.dp,
                    end = 16.dp,
                    bottom = 28.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (passwordEditorOpen) {
                item {
                    ChangePasswordSectionHeader(
                        onBack = {
                            if (passwordChangeSucceeded) {
                                onPasswordChanged()
                            } else {
                                passwordEditorOpen = false
                            }
                        }
                    )
                }
                item {
                    ChangePasswordForm(
                        onSubmitPasswordChange = { currentPassword, newPassword, confirmNewPassword
                            ->
                            repository.changePassword(
                                session,
                                currentPassword,
                                newPassword,
                                confirmNewPassword,
                            )
                        },
                        onClose = { passwordEditorOpen = false },
                        onContinueToLogin = onPasswordChanged,
                        onSuccessStateChanged = { passwordChangeSucceeded = it },
                    )
                }
            } else if (activeSection == null) {
                item {
                    Text(
                        text = stringResource(R.string.my_page),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
                item {
                    ProfileCard(
                        session = session,
                        onEditAvatar = { avatarActionsOpen = true },
                        onChangePassword = {
                            passwordChangeSucceeded = false
                            passwordEditorOpen = true
                        },
                        avatarError = avatarError,
                    )
                }
                item {
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                item {
                    MyPageSettingsTabs(onOpenSection = { settingsSection = it })
                }
                item { MyPageSettingsFooter(onLogout = onLogout) }
            } else {
                item {
                    MyPageSectionHeader(
                        section = activeSection,
                        onBack = { settingsSection = null },
                    )
                }
                item {
                    MyPageSettingsContent(
                        section = activeSection,
                        state = settingsState,
                        onInterfaceLocaleChange = settingsViewModel::setInterfaceLocaleMode,
                        onMetadataLanguageChange = settingsViewModel::setMetadataLanguage,
                        onPlaybackPreferenceChange = settingsViewModel::setPlaybackPreference,
                        onPlayerEngineChange = settingsViewModel::setPlayerEngine,
                        onShowDebugIconChange = settingsViewModel::setShowDebugIcon,
                        onCheckForUpdatesOnStartupChange =
                            settingsViewModel::setCheckForUpdatesOnStartup,
                        onSubtitleChange = settingsViewModel::updateSubtitle,
                    )
                }
            }
        }
    }

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

@Composable
private fun MyPageSectionHeader(
    section: MyPageSettingsSection,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_arrow_left),
                contentDescription = stringResource(R.string.back),
            )
        }
        Text(
            text =
                when (section) {
                    MyPageSettingsSection.Appearance -> stringResource(R.string.appearance_group)
                    MyPageSettingsSection.Player -> stringResource(R.string.player_group)
                    MyPageSettingsSection.Subtitles -> stringResource(R.string.subtitles_group)
                    MyPageSettingsSection.Updates -> stringResource(R.string.updates_group)
                },
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
private fun ChangePasswordSectionHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_arrow_left),
                contentDescription = stringResource(R.string.back),
            )
        }
        Text(
            text = stringResource(R.string.change_password),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
    }
}

@Composable
internal fun ChangePasswordForm(
    onSubmitPasswordChange: suspend (String, String, String) -> Unit,
    onClose: () -> Unit,
    onContinueToLogin: () -> Unit,
    onSuccessStateChanged: (Boolean) -> Unit = {},
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmNewPassword by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var success by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val passwordTooShortMessage = stringResource(R.string.password_too_short)
    val passwordsDoNotMatchMessage = stringResource(R.string.passwords_do_not_match)
    val passwordChangeFailedMessage = stringResource(R.string.password_change_failed)

    if (success) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.password_changed),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.password_changed_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onContinueToLogin, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.continue_to_login))
            }
        }
        return
    }

    fun submit() {
        if (submitting) return
        error =
            when {
                newPassword.length < 8 -> passwordTooShortMessage
                newPassword != confirmNewPassword -> passwordsDoNotMatchMessage
                else -> null
            }
        if (error != null) return

        submitting = true
        scope.launch {
            runCatching {
                    onSubmitPasswordChange(currentPassword, newPassword, confirmNewPassword)
                }
                .onSuccess {
                    success = true
                    onSuccessStateChanged(true)
                }
                .onFailure { error = passwordChangeFailedMessage }
            submitting = false
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.password_change_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        PasswordField(
            label = stringResource(R.string.current_password),
            value = currentPassword,
            onValueChange = {
                currentPassword = it
                error = null
            },
            imeAction = ImeAction.Next,
            enabled = !submitting,
        )
        PasswordField(
            label = stringResource(R.string.new_password),
            value = newPassword,
            onValueChange = {
                newPassword = it
                error = null
            },
            imeAction = ImeAction.Next,
            enabled = !submitting,
        )
        PasswordField(
            label = stringResource(R.string.confirm_new_password),
            value = confirmNewPassword,
            onValueChange = {
                confirmNewPassword = it
                error = null
            },
            imeAction = ImeAction.Done,
            onDone = ::submit,
            enabled = !submitting,
        )
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(onClick = onClose, enabled = !submitting) {
                Text(stringResource(R.string.cancel))
            }
            Button(
                onClick = ::submit,
                enabled =
                    !submitting &&
                        currentPassword.isNotBlank() &&
                        newPassword.isNotBlank() &&
                        confirmNewPassword.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

@Composable
private fun PasswordField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    imeAction: ImeAction,
    enabled: Boolean,
    onDone: (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = imeAction,
            ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AvatarActionSheet(
    hasAvatar: Boolean,
    onDismiss: () -> Unit,
    onUpload: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetSurface = MaterialTheme.colorScheme.background
    val actionSurface = MaterialTheme.colorScheme.surfaceVariant
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
                    .padding(bottom = 16.dp)
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
            TextButton(
                onClick = onConfirm,
                enabled = !deleting,
                colors =
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
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
    onChangePassword: () -> Unit = {},
    avatarError: String? = null,
) {
    BoxWithConstraints {
        val compact = maxWidth < 360.dp
        Card(
            colors =
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (compact) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ProfileIdentity(session, avatarError)
                    AvatarButton(onEditAvatar, Modifier.fillMaxWidth(), session)
                    ChangePasswordButton(onChangePassword, Modifier.fillMaxWidth())
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    UserAvatar(
                        session = session,
                        userId = session.userId,
                        username = session.username,
                        modifier = Modifier.size(96.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        ProfileDetails(session, avatarError)
                        Spacer(Modifier.height(12.dp))
                        AvatarButton(onEditAvatar, Modifier.fillMaxWidth(), session)
                        ChangePasswordButton(onChangePassword, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileIdentity(session: AuthSession, avatarError: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        UserAvatar(
            session = session,
            userId = session.userId,
            username = session.username,
            modifier = Modifier.size(96.dp),
        )
        ProfileDetails(session, avatarError, Modifier.weight(1f))
    }
}

@Composable
private fun ProfileDetails(
    session: AuthSession,
    avatarError: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
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
        avatarError?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AvatarButton(
    onEditAvatar: () -> Unit,
    modifier: Modifier,
    session: AuthSession,
) {
    OutlinedButton(onClick = onEditAvatar, modifier = modifier) {
        Text(
            stringResource(
                if (session.avatarVersion == null) R.string.add_avatar else R.string.change_avatar
            )
        )
    }
}

@Composable
private fun ChangePasswordButton(
    onClick: () -> Unit,
    modifier: Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text(stringResource(R.string.change_password))
    }
}

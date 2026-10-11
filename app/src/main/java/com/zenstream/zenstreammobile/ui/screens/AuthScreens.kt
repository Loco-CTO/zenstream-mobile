package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
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
import com.zenstream.zenstreammobile.data.normalizeServerUrl
import com.zenstream.zenstreammobile.data.serverRequiresLocalNetworkPermission
import com.zenstream.zenstreammobile.ui.LoginViewModel
import kotlinx.coroutines.launch

@Composable
fun ServerSetupScreen(
    initialServerUrl: String? = null,
    localNetworkPermissionGranted: Boolean = true,
    isLocalNetworkEndpoint: suspend (String) -> Boolean = {
        serverRequiresLocalNetworkPermission(it)
    },
    onRequestLocalNetworkPermission: ((Boolean) -> Unit) -> Unit = { result -> result(true) },
    onOpenAppSettings: () -> Unit = {},
    onConfigured: suspend (String) -> Unit,
) {
    var server by rememberSaveable { mutableStateOf(initialServerUrl.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var localNetworkPermissionDenied by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val invalidUrlMessage = stringResource(R.string.server_url_invalid)
    val localNetworkPermissionDeniedMessage =
        stringResource(R.string.local_network_permission_denied)
    val focusManager = LocalFocusManager.current
    LaunchedEffect(initialServerUrl) {
        if (!initialServerUrl.isNullOrBlank()) server = initialServerUrl
    }
    LaunchedEffect(localNetworkPermissionGranted) {
        if (localNetworkPermissionGranted && localNetworkPermissionDenied) {
            localNetworkPermissionDenied = false
            if (error == localNetworkPermissionDeniedMessage) error = null
        }
    }
    AuthContainer {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_lock),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.server_setup_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.server_setup_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(8.dp))
        OutlinedTextField(
            value = server,
            onValueChange = {
                server = it
                error = null
                localNetworkPermissionDenied = false
            },
            label = { Text(stringResource(R.string.orchestrator_url)) },
            placeholder = { Text(stringResource(R.string.server_url_hint)) },
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
            isError = error != null,
            supportingText = { error?.let { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                scope.launch {
                    busy = true
                    val normalizedServer = runCatching { normalizeServerUrl(server) }.getOrNull()
                    if (normalizedServer == null) {
                        error = invalidUrlMessage
                        busy = false
                        return@launch
                    }
                    error = null
                    if (isLocalNetworkEndpoint(normalizedServer)) {
                        focusManager.clearFocus()
                        onRequestLocalNetworkPermission { granted ->
                            if (granted) {
                                scope.launch {
                                    localNetworkPermissionDenied = false
                                    runCatching { onConfigured(normalizedServer) }
                                        .onFailure { error = invalidUrlMessage }
                                    busy = false
                                }
                            } else {
                                localNetworkPermissionDenied = true
                                error = localNetworkPermissionDeniedMessage
                                busy = false
                            }
                        }
                    } else {
                        runCatching { onConfigured(normalizedServer) }
                            .onFailure { error = invalidUrlMessage }
                        busy = false
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        ) {
            AuthButtonContent(loading = busy) {
                Text(stringResource(R.string.continue_label))
            }
        }
        if (localNetworkPermissionDenied) {
            TextButton(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.local_network_permission_open_settings))
            }
        }
    }
}

@Composable
fun LoginScreen(repository: CatalogRepository, onChangeServer: () -> Unit) {
    val vm: LoginViewModel = viewModel(factory = LoginViewModel.Factory(repository))
    val state by vm.uiState.collectAsStateWithLifecycle()
    var password by remember { mutableStateOf("") }
    AuthContainer {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_lock),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.welcome),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.login_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(8.dp))
        OutlinedTextField(
            value = state.username,
            onValueChange = vm::updateUsername,
            label = { Text(stringResource(R.string.username)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { vm.login(password) }),
            modifier = Modifier.fillMaxWidth(),
        )
        state.error?.let {
            Text(
                stringResource(R.string.login_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = { vm.login(password) },
            enabled = !state.busy && state.username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            AuthButtonContent(loading = state.busy) {
                Text(stringResource(R.string.login))
            }
        }
        OutlinedButton(
            onClick = onChangeServer,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.change_server))
        }
    }
}

@Composable
private fun AuthButtonContent(loading: Boolean, label: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            label()
        }
    }
}

@Composable
private fun AuthContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().imePadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

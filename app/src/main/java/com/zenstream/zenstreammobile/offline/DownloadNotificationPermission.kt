package com.zenstream.zenstreammobile.offline

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.zenstream.zenstreammobile.R

/** One startup introduction; blocked transfers use the same controller for explicit recovery. */
@Composable
internal fun rememberNotificationPermissionHandler(requestOnStartup: Boolean): () -> Unit {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val preferences =
        remember(context) {
            context.getSharedPreferences("download-notifications", Context.MODE_PRIVATE)
        }
    var prompt by rememberSaveable { mutableStateOf<String?>(null) }
    var recovering by rememberSaveable { mutableStateOf(false) }
    var recoveryShown by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && recovering) prompt = "recovery"
            recovering = false
        }
    LaunchedEffect(requestOnStartup) {
        if (
            requestOnStartup &&
                Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED &&
                !preferences.getBoolean("startup-shown", false)
        ) {
            preferences.edit { putBoolean("startup-shown", true) }
            prompt = "startup"
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (downloadNotificationsEnabled(context)) {
            prompt = null
            recoveryShown = false
        }
    }
    if (prompt != null) {
        val startup = prompt == "startup"
        val canRequest =
            Build.VERSION.SDK_INT >= 33 &&
                activity != null &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED &&
                (!preferences.getBoolean("permission-requested", false) ||
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ))
        NotificationPermissionDialog(
            startup = startup,
            canRequest = canRequest,
            onDismiss = { prompt = null },
            onConfirm = {
                recovering = !startup
                prompt = null
                if (canRequest) {
                    preferences.edit { putBoolean("permission-requested", true) }
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else openDownloadNotificationSettings(context)
            },
        )
    }
    return {
        if (!downloadNotificationsEnabled(context) && !recoveryShown) {
            recoveryShown = true
            prompt = "recovery"
        }
    }
}

internal fun downloadNotificationsEnabled(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 26 ||
            context
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(DownloadExecution.CHANNEL)
                ?.importance != NotificationManager.IMPORTANCE_NONE)

internal fun openDownloadNotificationSettings(context: Context) {
    val channel =
        if (Build.VERSION.SDK_INT >= 26)
            context
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(DownloadExecution.CHANNEL)
        else null
    val intent =
        if (Build.VERSION.SDK_INT >= 26) {
            Intent(
                    if (
                        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                            channel != null
                    )
                        Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                    else Settings.ACTION_APP_NOTIFICATION_SETTINGS
                )
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, DownloadExecution.CHANNEL)
        } else
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null),
            )
    context.startActivity(intent)
}

@Composable
internal fun DownloadNotificationSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(downloadNotificationsEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = downloadNotificationsEnabled(context)
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.download_notification_settings),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(
                    if (enabled) R.string.download_notification_enabled
                    else R.string.download_notification_disabled
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { openDownloadNotificationSettings(context) }) {
                Text(stringResource(R.string.download_notification_open_settings))
            }
        }
    }
}

@Composable
internal fun NotificationPermissionDialog(
    startup: Boolean,
    canRequest: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (startup) R.string.notification_permission_title
                    else R.string.notification_recovery_title
                )
            )
        },
        text = {
            Text(
                stringResource(
                    when {
                        startup -> R.string.notification_permission_message
                        canRequest -> R.string.notification_recovery_message
                        else -> R.string.notification_recovery_settings_message
                    }
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(
                        if (canRequest) R.string.notification_permission_allow
                        else R.string.download_notification_open_settings
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.notification_permission_not_now))
            }
        },
    )
}

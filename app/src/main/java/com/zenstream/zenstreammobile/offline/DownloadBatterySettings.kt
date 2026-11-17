package com.zenstream.zenstreammobile.offline

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.zenstream.zenstreammobile.R

@StringRes
internal fun downloadBatteryInstructions(manufacturer: String, brand: String): Int {
    val vendors = listOf(manufacturer.trim().lowercase(), brand.trim().lowercase())
    if (vendors.any { it == "xiaomi" || it == "redmi" || it == "poco" })
        return R.string.download_battery_xiaomi
    return if ("samsung" in vendors) R.string.download_battery_samsung
    else R.string.download_battery_android
}

private fun openDownloadBatterySettings(context: Context): Boolean {
    val appDetails =
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        )
    val optimizationList = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    for (intent in listOf(appDetails, optimizationList)) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: ActivityNotFoundException) {
            // Some Android builds omit a settings activity.
        } catch (_: SecurityException) {
            // Some builds restrict settings activities to system apps.
        }
    }
    Toast.makeText(context, R.string.download_battery_settings_unavailable, Toast.LENGTH_SHORT)
        .show()
    return false
}

@Composable
internal fun rememberDownloadBatteryPrompt(onClosed: () -> Unit): () -> Boolean {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val preferences =
        remember(context) {
            context.getSharedPreferences("download-battery", Context.MODE_PRIVATE)
        }
    var show by rememberSaveable { mutableStateOf(false) }
    var openedSettings by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (openedSettings) {
            openedSettings = false
            onClosed()
        }
    }
    if (show) {
        val dismiss = {
            show = false
            onClosed()
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.download_battery_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.download_battery_prompt))
                    Text(
                        stringResource(downloadBatteryInstructions(Build.MANUFACTURER, Build.BRAND))
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        show = false
                        openedSettings = openDownloadBatterySettings(activity ?: context)
                        if (!openedSettings) onClosed()
                    }
                ) {
                    Text(stringResource(R.string.download_battery_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = dismiss) {
                    Text(stringResource(R.string.notification_permission_not_now))
                }
            },
        )
    }
    return {
        if (activity != null && !preferences.getBoolean("prompt-shown", false)) {
            preferences.edit { putBoolean("prompt-shown", true) }
            show = true
        }
        show
    }
}

@Composable
internal fun DownloadBatterySettings() {
    val context = LocalActivity.current ?: LocalContext.current
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.download_battery_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(downloadBatteryInstructions(Build.MANUFACTURER, Build.BRAND)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { openDownloadBatterySettings(context) }) {
                Text(stringResource(R.string.download_battery_settings))
            }
        }
    }
}

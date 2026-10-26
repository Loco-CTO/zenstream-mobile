package com.zenstream.zenstreammobile.offline

import androidx.compose.runtime.Composable
import com.zenstream.zenstreammobile.model.MediaItem

@Composable
fun OfflineEntryScreen(
    runtime: OfflineRuntime,
    onOnline: () -> Unit,
    onItem: ((OfflineProfile, MediaItem) -> Unit)? = null,
) {
    val actions = LocalDownloadUi.current
    DownloadsScreen(
        runtime,
        onOnline = onOnline,
        onItem = onItem ?: actions?.playSaved ?: { _, _ -> },
    )
}

package com.zenstream.zenstreammobile.offline

import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.model.PlaybackData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun ResolvedPlaybackSource.Local.knownHdr(): Boolean =
    org.json.JSONObject(asset.descriptor).optJSONArray("trackManifest")?.objects().orEmpty().any {
        it.optBoolean("isHdr") ||
            it.optString("colorTransfer").lowercase() in
                setOf("smpte2084", "arib-std-b67", "pq", "hlg")
    }

/** Only subtitles whose saved files are available enter the local text selector. */
internal fun ResolvedPlaybackSource.Local.videoData(): PlaybackData {
    val subtitles =
        supplements
            .filter { it.key.startsWith("subtitle:") }
            .mapNotNull { asset ->
                val index = asset.key.substringAfter(':').toIntOrNull() ?: return@mapNotNull null
                MediaStream(
                    index,
                    "Subtitle",
                    displayTitle = asset.descriptor.optString("title").takeIf { it.isNotBlank() },
                    language = asset.descriptor.optString("language").takeIf { it.isNotBlank() },
                    codec = "webvtt",
                    isDefault = asset.descriptor.optBoolean("isDefault"),
                )
            }
            .distinctBy { it.index }
    return data.copy(subtitles = subtitles)
}

internal suspend fun ResolvedPlaybackSource.Local.subtitleText(index: Int): String? =
    withContext(Dispatchers.IO) {
        supplements
            .firstOrNull { it.key == "subtitle:$index" }
            ?.file
            ?.takeIf { it.length() <= 16777216 }
            ?.readText()
    }

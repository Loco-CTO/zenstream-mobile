package com.zenstream.zenstreammobile.offline

import com.zenstream.zenstreammobile.model.PlaybackData
import org.json.JSONObject

/** Convert a requested source track into the actual downloaded representation's audio ordinal. */
internal fun downloadedAudioOrdinal(data: PlaybackData, descriptor: JSONObject): Int {
    val options = descriptor.optJSONObject("downloadOptions") ?: JSONObject()
    val manifest = descriptor.optJSONArray("trackManifest")?.objects().orEmpty()
    val source = if (options.isNull("audioStreamId")) null else options.getInt("audioStreamId")
    val language = options.optString("audioLanguage").takeIf { it.isNotBlank() }
    val output =
        manifest
            .firstOrNull { track ->
                track.optString("type") == "audio" &&
                    source != null &&
                    (if (track.has("sourceIndex")) track.optInt("sourceIndex")
                    else track.optInt("index")) == source
            }
            ?.optInt("index")
    return data.audioTracks.indexOfFirst { it.index == output }.takeIf { it >= 0 }
        ?: language?.let { selected ->
            data.audioTracks
                .indexOfFirst {
                    languageKey(it.language.orEmpty()) == languageKey(selected)
                }
                .takeIf { it >= 0 }
        }
        ?: data.audioTracks.indexOfFirst { it.isDefault }.takeIf { it >= 0 }
        ?: 0
}

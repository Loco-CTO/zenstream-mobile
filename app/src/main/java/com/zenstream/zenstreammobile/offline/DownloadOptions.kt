package com.zenstream.zenstreammobile.offline

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class DownloadSpec(
    val id: String,
    val title: String,
    val kind: String = "catalog",
    val type: String? = null,
    val durationSeconds: Double? = null,
)

internal val downloadableTextSubtitles =
    setOf("srt", "subrip", "webvtt", "ass", "ssa", "mov_text", "text")

internal fun isAudioDownload(spec: DownloadSpec): Boolean =
    spec.kind == "playlist" ||
        spec.type?.lowercase(Locale.ROOT) in
            setOf("audio", "track", "release", "musicalbum", "artist", "musicartist")

internal fun downloadProfiles(
    spec: DownloadSpec,
    capabilities: JSONObject,
    hdr: Boolean = false,
): List<String> {
    val available = capabilities.optJSONArray("profiles") ?: return emptyList()
    return List(available.length()) { available.getString(it) }
        .filter {
            it == "original" ||
                isAudioDownload(spec) && it.startsWith("audio-") ||
                !isAudioDownload(spec) &&
                    it.startsWith("video-") &&
                    (!hdr || capabilities.optBoolean("hdrToneMapping"))
        }
}

internal fun estimateDownloadBytes(profile: String, source: JSONObject?): Long? {
    if (source == null) return null
    if (profile == "original") return source.optLong("sizeBytes").takeIf { it > 0 }
    val duration = source.optDouble("durationSeconds")
    if (!duration.isFinite() || duration <= 0) return null
    val bitrate =
        when (profile) {
            "video-720p" -> 2660000
            "video-1080p" -> 5160000
            "audio-128k" -> 128000
            "audio-256k" -> 256000
            else -> return null
        }
    return (duration * bitrate / 8).takeIf { it.isFinite() && it <= Long.MAX_VALUE }?.toLong()
}

private val languageAliases by lazy {
    buildMap {
        Locale.getISOLanguages().forEach { code ->
            runCatching { Locale.forLanguageTag(code).isO3Language }
                .getOrNull()
                ?.let { put(it, code) }
        }
        putAll(
            mapOf(
                "chi" to "zh",
                "fre" to "fr",
                "ger" to "de",
                "dut" to "nl",
                "gre" to "el",
                "cze" to "cs",
                "rum" to "ro",
                "slo" to "sk",
            )
        )
    }
}

internal fun languageKey(value: String): String {
    val base = value.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
    return languageAliases[base] ?: base
}

internal fun streamLanguage(stream: JSONObject): String =
    stream.optJSONObject("tags")?.optString("language")?.takeIf { it.isNotBlank() }
        ?: stream.optString("language")

internal fun streamType(stream: JSONObject): String =
    stream.optString("codec_type").ifBlank { stream.optString("type") }

internal fun languageSelections(source: JSONObject, options: JSONObject): JSONObject {
    val result = JSONObject(options.toString())
    val streams = source.optJSONArray("streams")?.objects().orEmpty()
    val audio = options.optString("audioLanguage").trim()
    if (audio.isNotBlank()) {
        val selected =
            streams.firstOrNull {
                streamType(it) == "audio" && languageKey(streamLanguage(it)) == languageKey(audio)
            } ?: error("The preferred audio language is unavailable for this item.")
        result.put("audioStreamId", selected.getInt("index"))
    }
    val languages = options.optJSONArray("subtitleLanguages")
    if (languages != null && languages.length() > 0) {
        val selected = JSONArray()
        for (index in 0 until languages.length()) {
            val key = languageKey(languages.getString(index))
            val matches = streams.filter {
                streamType(it) == "subtitle" && languageKey(streamLanguage(it)) == key
            }
            val chosen =
                matches.firstOrNull { it.optJSONObject("disposition")?.optInt("default") == 1 }
                    ?: matches.firstOrNull()
                    ?: error("A selected subtitle language is unavailable for this item.")
            selected.put(chosen.getInt("index"))
        }
        result.put("subtitleStreamIndices", selected)
    }
    if (source.has("id")) result.put("sourceId", source.getString("id"))
    return result
}

internal fun sameDownloadChoices(requested: JSONObject, existing: JSONObject): Boolean =
    listOf(
            "sourceId",
            "audioStreamId",
            "audioLanguage",
            "subtitleStreamIndices",
            "subtitleLanguages",
            "includeAllTracks",
            "language",
        )
        .all { key -> requested.opt(key)?.toString() == existing.opt(key)?.toString() }

package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.ArtistCredit
import com.zenstream.zenstreammobile.model.AudioQueueEntry
import com.zenstream.zenstreammobile.model.AudioQueueSnapshot
import com.zenstream.zenstreammobile.model.AudioRepeatMode
import com.zenstream.zenstreammobile.model.MediaItem
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject

internal const val AUDIO_QUEUE_SCHEMA_VERSION = 1
private const val AUDIO_QUEUE_MAX_ENTRIES = 500
private const val AUDIO_QUEUE_MAX_TEXT_LENGTH = 2_048
private const val AUDIO_QUEUE_MAX_SOURCE_TEXT_LENGTH = 64
private const val AUDIO_QUEUE_MAX_POSITION_SECONDS = 7 * 24 * 60 * 60.0

internal fun audioQueueScope(serverUrl: String, userId: String): String =
    "${normalizeServerUrl(serverUrl)}\u0000$userId"

internal fun AudioQueueSnapshot.toJson(): JSONObject {
    val persistedEntryIds =
        entries.take(AUDIO_QUEUE_MAX_ENTRIES).mapTo(mutableSetOf()) { it.entryId }
    return JSONObject()
        .put("schemaVersion", AUDIO_QUEUE_SCHEMA_VERSION)
        .put("serverUrl", normalizeServerUrl(serverUrl).take(AUDIO_QUEUE_MAX_TEXT_LENGTH))
        .put("userId", userId.take(AUDIO_QUEUE_MAX_TEXT_LENGTH))
        .put(
            "entries",
            JSONArray().apply {
                entries.take(AUDIO_QUEUE_MAX_ENTRIES).forEach { entry ->
                    put(
                        JSONObject()
                            .put("entryId", entry.entryId.take(AUDIO_QUEUE_MAX_TEXT_LENGTH))
                            .put(
                                "playbackInstanceId",
                                entry.playbackInstanceId?.take(AUDIO_QUEUE_MAX_TEXT_LENGTH),
                            )
                            .put("track", entry.track.toAudioQueueJson())
                    )
                }
            },
        )
        .put(
            "playedEntryIds",
            JSONArray().apply {
                playedEntryIds
                    .asSequence()
                    .filter { it in persistedEntryIds }
                    .map { it.take(AUDIO_QUEUE_MAX_TEXT_LENGTH) }
                    .sorted()
                    .take(AUDIO_QUEUE_MAX_ENTRIES)
                    .forEach(::put)
            },
        )
        .put("currentIndex", currentIndex)
        .put("positionSeconds", positionSeconds.coerceIn(0.0, AUDIO_QUEUE_MAX_POSITION_SECONDS))
        .put("shuffle", shuffle)
        .put("repeatMode", repeatMode.name)
        .put(
            "durationSeconds",
            durationSeconds
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.coerceAtMost(AUDIO_QUEUE_MAX_POSITION_SECONDS),
        )
        .put("sourceEntryId", sourceEntryId?.take(AUDIO_QUEUE_MAX_TEXT_LENGTH))
        .put("sourceFormat", sourceFormat?.take(AUDIO_QUEUE_MAX_SOURCE_TEXT_LENGTH))
        .put("sourceBitrate", sourceBitrate?.takeIf { it > 0 })
        .put("sourceSampleRate", sourceSampleRate?.takeIf { it > 0 })
        .put("playbackMode", playbackMode?.take(AUDIO_QUEUE_MAX_SOURCE_TEXT_LENGTH))
        .put("updatedAt", updatedAt)
}

private fun MediaItem.toAudioQueueJson(): JSONObject =
    JSONObject()
        .put("id", id.take(AUDIO_QUEUE_MAX_TEXT_LENGTH))
        .put("name", name.take(AUDIO_QUEUE_MAX_TEXT_LENGTH))
        .put("type", type)
        .put("albumId", albumId?.take(AUDIO_QUEUE_MAX_TEXT_LENGTH))
        .put("artistId", artistId?.take(AUDIO_QUEUE_MAX_TEXT_LENGTH))
        .put("album", album?.take(AUDIO_QUEUE_MAX_TEXT_LENGTH))
        .put("albumArtist", albumArtist?.take(AUDIO_QUEUE_MAX_TEXT_LENGTH))
        .put("albumType", albumType?.take(AUDIO_QUEUE_MAX_TEXT_LENGTH))
        .put("artists", JSONArray(artists.take(32).map { it.take(AUDIO_QUEUE_MAX_TEXT_LENGTH) }))
        .put(
            "artistCredits",
            JSONArray().apply {
                artistCredits.forEach { credit ->
                    put(
                        JSONObject()
                            .put("id", credit.id?.take(AUDIO_QUEUE_MAX_TEXT_LENGTH))
                            .put("name", credit.name.take(AUDIO_QUEUE_MAX_TEXT_LENGTH))
                            .put("joinPhrase", credit.joinPhrase?.take(128))
                    )
                }
            },
        )
        .put("discNumber", discNumber)
        .put("trackNumber", trackNumber)
        .put("durationSeconds", durationSeconds)
        .put("primaryImageTag", safeQueueArtworkTag(imageTags["Primary"]))
        .put(
            "primaryImageBlurHash",
            imageBlurHashes["Primary"]?.take(AUDIO_QUEUE_MAX_TEXT_LENGTH),
        )

internal fun audioQueueSnapshotFromJson(value: JSONObject): AudioQueueSnapshot? {
    if (value.optInt("schemaVersion", -1) != AUDIO_QUEUE_SCHEMA_VERSION) return null
    val serverUrl =
        value.optString("serverUrl").take(AUDIO_QUEUE_MAX_TEXT_LENGTH).takeIf(String::isNotBlank)
            ?: return null
    val userId =
        value.optString("userId").take(AUDIO_QUEUE_MAX_TEXT_LENGTH).takeIf(String::isNotBlank)
            ?: return null
    val normalizedServerUrl =
        runCatching { normalizeServerUrl(serverUrl) }.getOrNull() ?: return null
    val entries =
        value
            .optJSONArray("entries")
            ?.let { array ->
                val ids = mutableSetOf<String>()
                List(minOf(array.length(), AUDIO_QUEUE_MAX_ENTRIES)) { index ->
                        val raw = array.optJSONObject(index) ?: return@List null
                        val entryId =
                            raw.optString("entryId")
                                .take(AUDIO_QUEUE_MAX_TEXT_LENGTH)
                                .takeIf(String::isNotBlank) ?: return@List null
                        if (!ids.add(entryId)) return@List null
                        val track =
                            audioQueueTrackFromJson(raw.optJSONObject("track")) ?: return@List null
                        AudioQueueEntry(
                            entryId = entryId,
                            track = track,
                            playbackInstanceId =
                                raw.optString("playbackInstanceId")
                                    .take(AUDIO_QUEUE_MAX_TEXT_LENGTH)
                                    .ifBlank { null },
                        )
                    }
                    .filterNotNull()
            }
            .orEmpty()
    if (entries.isEmpty()) return null
    val repeatMode =
        runCatching { AudioRepeatMode.valueOf(value.optString("repeatMode")) }
            .getOrDefault(AudioRepeatMode.Off)
    val entryIds = entries.mapTo(mutableSetOf()) { it.entryId }
    val playedEntryIds =
        value
            .optJSONArray("playedEntryIds")
            ?.let { array ->
                mutableSetOf<String>().apply {
                    repeat(minOf(array.length(), AUDIO_QUEUE_MAX_ENTRIES)) { index ->
                        val entryId =
                            array
                                .optString(index)
                                .take(AUDIO_QUEUE_MAX_TEXT_LENGTH)
                                .takeIf(String::isNotBlank)
                        if (entryId != null && entryId in entryIds) add(entryId)
                    }
                }
            }
            .orEmpty()
    val sourceEntryId =
        value.optString("sourceEntryId").take(AUDIO_QUEUE_MAX_TEXT_LENGTH).takeIf {
            it.isNotBlank() && it in entryIds
        }
    val sourceMetadataMatchesCurrent =
        sourceEntryId ==
            entries
                .getOrNull(value.optInt("currentIndex", 0).coerceIn(0, entries.lastIndex))
                ?.entryId
    return AudioQueueSnapshot(
        schemaVersion = AUDIO_QUEUE_SCHEMA_VERSION,
        serverUrl = normalizedServerUrl,
        userId = userId,
        entries = entries,
        currentIndex = value.optInt("currentIndex", 0).coerceIn(0, entries.lastIndex),
        positionSeconds =
            value
                .optDouble("positionSeconds", 0.0)
                .takeIf(Double::isFinite)
                ?.coerceIn(0.0, AUDIO_QUEUE_MAX_POSITION_SECONDS) ?: 0.0,
        shuffle = value.optBoolean("shuffle", false),
        repeatMode = repeatMode,
        updatedAt = value.optLong("updatedAt", 0L).coerceAtLeast(0L),
        playedEntryIds = playedEntryIds,
        durationSeconds =
            value
                .optDoubleOrNull("durationSeconds")
                ?.takeIf { sourceMetadataMatchesCurrent }
                ?.coerceIn(0.0, AUDIO_QUEUE_MAX_POSITION_SECONDS),
        sourceEntryId = sourceEntryId,
        sourceFormat =
            value.optString("sourceFormat").take(AUDIO_QUEUE_MAX_SOURCE_TEXT_LENGTH).takeIf {
                sourceMetadataMatchesCurrent && it.isNotBlank()
            },
        sourceBitrate =
            value.optIntOrNull("sourceBitrate")?.takeIf { sourceMetadataMatchesCurrent && it > 0 },
        sourceSampleRate =
            value.optIntOrNull("sourceSampleRate")?.takeIf {
                sourceMetadataMatchesCurrent && it > 0
            },
        playbackMode =
            value.optString("playbackMode").take(AUDIO_QUEUE_MAX_SOURCE_TEXT_LENGTH).takeIf {
                sourceMetadataMatchesCurrent && it.isNotBlank()
            },
    )
}

private fun audioQueueTrackFromJson(value: JSONObject?): MediaItem? {
    value ?: return null
    val id =
        value.optString("id").take(AUDIO_QUEUE_MAX_TEXT_LENGTH).takeIf(String::isNotBlank)
            ?: return null
    val name = value.optString("name").take(AUDIO_QUEUE_MAX_TEXT_LENGTH).ifBlank { "Untitled" }
    val artists =
        value
            .optJSONArray("artists")
            ?.let { array ->
                List(minOf(array.length(), 32)) {
                        array.optString(it).take(AUDIO_QUEUE_MAX_TEXT_LENGTH)
                    }
                    .filter(String::isNotBlank)
            }
            .orEmpty()
    val credits =
        value
            .optJSONArray("artistCredits")
            ?.let { array ->
                List(array.length()) { index ->
                        val credit = array.optJSONObject(index) ?: return@List null
                        ArtistCredit(
                            id =
                                credit.optString("id").take(AUDIO_QUEUE_MAX_TEXT_LENGTH).ifBlank {
                                    null
                                },
                            name = credit.optString("name").take(AUDIO_QUEUE_MAX_TEXT_LENGTH),
                            joinPhrase =
                                if (credit.has("joinPhrase"))
                                    credit.optString("joinPhrase").take(128)
                                else null,
                        )
                    }
                    .filterNotNull()
                    .filter { it.name.isNotBlank() }
            }
            .orEmpty()
    val primaryImageTag = safeQueueArtworkTag(value.optString("primaryImageTag").ifBlank { null })
    val primaryImageBlurHash =
        value.optString("primaryImageBlurHash").take(AUDIO_QUEUE_MAX_TEXT_LENGTH).ifBlank { null }
    return MediaItem(
        id = id,
        name = name,
        type = value.optString("type").ifBlank { "Audio" }.takeIf { it == "Audio" } ?: return null,
        albumId = value.optString("albumId").take(AUDIO_QUEUE_MAX_TEXT_LENGTH).ifBlank { null },
        artistId = value.optString("artistId").take(AUDIO_QUEUE_MAX_TEXT_LENGTH).ifBlank { null },
        album = value.optString("album").take(AUDIO_QUEUE_MAX_TEXT_LENGTH).ifBlank { null },
        albumArtist =
            value.optString("albumArtist").take(AUDIO_QUEUE_MAX_TEXT_LENGTH).ifBlank { null },
        albumType = value.optString("albumType").take(AUDIO_QUEUE_MAX_TEXT_LENGTH).ifBlank { null },
        artists = artists,
        artistCredits = credits,
        discNumber = value.optIntOrNull("discNumber"),
        trackNumber = value.optIntOrNull("trackNumber"),
        durationSeconds = value.optDoubleOrNull("durationSeconds"),
        imageTags = primaryImageTag?.let { mapOf("Primary" to it) }.orEmpty(),
        imageBlurHashes = primaryImageBlurHash?.let { mapOf("Primary" to it) }.orEmpty(),
    )
}

/** Queue snapshots may keep catalog artwork metadata, but never access-bearing URLs. */
private fun safeQueueArtworkTag(value: String?): String? {
    val tag = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val uri =
        try {
            URI(tag)
        } catch (_: IllegalArgumentException) {
            return null
        }
    val path = uri.path ?: return null
    if (uri.isAbsolute || uri.host != null) return null
    if (!path.matches(Regex("/api/catalog/items/[^/]+/images/Primary"))) return null
    if (
        uri.query.orEmpty().split('&').any { parameter ->
            val key = parameter.substringBefore('=').trim()
            key.equals("access", ignoreCase = true) ||
                key.equals("token", ignoreCase = true) ||
                key.equals("ticket", ignoreCase = true)
        }
    ) {
        return null
    }
    return tag.take(AUDIO_QUEUE_MAX_TEXT_LENGTH)
}

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (!has(key) || isNull(key)) null else optInt(key).takeIf { it != 0 || optString(key) == "0" }

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (!has(key) || isNull(key)) null else optDouble(key).takeIf(Double::isFinite)

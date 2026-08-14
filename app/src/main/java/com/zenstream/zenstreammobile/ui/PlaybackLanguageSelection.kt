package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.model.MediaStream

internal fun preferredTrackIndex(
    tracks: List<MediaStream>,
    language: String?,
): Int? =
    tracks
        .firstOrNull {
            language != null && it.language?.equals(language, ignoreCase = true) == true
        }
        ?.index ?: tracks.firstOrNull { it.isDefault }?.index ?: tracks.firstOrNull()?.index

internal fun preferredSubtitleIndex(
    tracks: List<MediaStream>,
    language: String?,
): Int? = if (language == "off") null else preferredTrackIndex(tracks, language)

package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.MediaItem

fun landscapeImageType(item: MediaItem): String? =
    when {
        item.type == "Episode" && item.imageTags["Primary"] != null -> "Primary"
        item.backdropImageTags.isNotEmpty() -> "Backdrop"
        else -> null
    }

fun posterImageType(item: MediaItem): String? =
    when {
        item.imageTags["Primary"] != null -> "Primary"
        item.type == "Episode" && item.seriesPrimaryImageTag != null -> "SeriesPrimary"
        else -> null
    }

fun seriesPosterImageType(item: MediaItem): String? =
    when {
        item.type == "Episode" && item.seriesPrimaryImageTag != null -> "SeriesPrimary"
        item.type == "Episode" -> null
        item.imageTags["Primary"] != null -> "Primary"
        else -> null
    }

fun imageUrl(serverUrl: String, item: MediaItem, type: String, width: Int, height: Int): String? {
    val imageType =
        when (type) {
            "SeriesPrimary" -> "Primary"
            else -> type
        }
    val id = if (type == "SeriesPrimary") item.seriesId else item.id
    val tag =
        if (type == "SeriesPrimary") item.seriesPrimaryImageTag
        else if (imageType == "Backdrop") item.backdropImageTags.firstOrNull()
        else item.imageTags[imageType]
    if (id.isNullOrBlank() || tag.isNullOrBlank()) return null
    if (tag.startsWith("/api/")) return "${serverUrl.trimEnd('/')}$tag"
    return null
}

fun imageBlurHash(item: MediaItem, type: String): String? =
    when (type) {
        "SeriesPrimary" -> item.seriesPrimaryImageBlurHash
        else -> item.imageBlurHashes[type]
    }

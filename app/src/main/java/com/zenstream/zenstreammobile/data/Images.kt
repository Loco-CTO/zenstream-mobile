package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.MediaItem
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

fun landscapeImageType(item: MediaItem): String? = when {
    item.imageTags["Thumb"] != null -> "Thumb"
    item.backdropImageTags.isNotEmpty() -> "Backdrop"
    item.imageTags["Primary"] != null -> "Primary"
    else -> null
}

fun posterImageType(item: MediaItem): String? = when {
    item.type == "Episode" && item.seriesId != null && item.seriesPrimaryImageTag != null -> "SeriesPrimary"
    item.imageTags["Primary"] != null -> "Primary"
    item.backdropImageTags.isNotEmpty() -> "Backdrop"
    item.imageTags["Thumb"] != null -> "Thumb"
    else -> null
}

fun imageUrl(serverUrl: String, item: MediaItem, type: String, width: Int, height: Int): String? {
    val imageType = when (type) {
        "SeriesPrimary" -> "Primary"
        else -> type
    }
    val id = if (type == "SeriesPrimary") item.seriesId else item.id
    val tag = if (type == "SeriesPrimary") item.seriesPrimaryImageTag
    else if (imageType == "Backdrop") item.backdropImageTags.firstOrNull()
    else item.imageTags[imageType]
    if (id.isNullOrBlank() || tag.isNullOrBlank()) return null
    val index = if (imageType == "Backdrop") "/0" else ""
    val encodedTag = URLEncoder.encode(tag, StandardCharsets.UTF_8.toString())
    return "${serverUrl.trimEnd('/')}/Items/$id/Images/$imageType$index?fillWidth=$width&fillHeight=$height&quality=90&tag=$encodedTag"
}

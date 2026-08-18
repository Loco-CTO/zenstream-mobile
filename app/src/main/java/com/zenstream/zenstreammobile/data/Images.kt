package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.MediaItem
import okhttp3.HttpUrl.Companion.toHttpUrl

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

/** Authenticated avatar route. A version is intentionally part of the URL cache key. */
fun userAvatarUrl(serverUrl: String, userId: String, avatarVersion: String? = null): String {
    val builder =
        serverUrl
            .trimEnd('/')
            .toHttpUrl()
            .newBuilder()
            .addPathSegments("api/users")
            .addPathSegment(userId)
            .addPathSegment("avatar")
    avatarVersion?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("v", it) }
    return builder.build().toString()
}

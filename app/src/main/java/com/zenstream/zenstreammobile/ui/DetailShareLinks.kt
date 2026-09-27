package com.zenstream.zenstreammobile.ui

import android.net.Uri
import com.zenstream.zenstreammobile.model.MediaItem

internal fun detailSharePath(item: MediaItem, selectedSeasonId: String? = null): String =
    when (item.type) {
        "BoxSet" -> "/collection/${Uri.encode(item.id)}"
        "Series" -> {
            val path = "/show/${Uri.encode(item.id)}"
            val seasonId = selectedSeasonId?.takeIf(String::isNotBlank)
            if (seasonId == null) path else "$path?seasonId=${Uri.encode(seasonId)}"
        }
        "Episode" -> {
            val seriesId = item.seriesId?.takeIf(String::isNotBlank)
            if (seriesId == null) "/show/${Uri.encode(item.id)}"
            else "/show/${Uri.encode(seriesId)}/episode/${Uri.encode(item.id)}"
        }
        "MusicAlbum" -> musicAlbumSharePath(item.id)
        "MusicArtist" -> musicArtistSharePath(item.id)
        "Audio" ->
            item.albumId?.let { musicAlbumSharePath(it, item.id) } ?: musicAlbumSharePath(item.id)
        else -> "/show/${Uri.encode(item.id)}"
    }

internal fun musicAlbumSharePath(albumId: String, selectedTrackId: String? = null): String {
    val path = "/album/${Uri.encode(albumId)}"
    val trackId = selectedTrackId?.takeIf(String::isNotBlank) ?: return path
    return "$path?trackId=${Uri.encode(trackId)}"
}

internal fun musicArtistSharePath(artistId: String): String = "/artist/${Uri.encode(artistId)}"

internal fun detailShareUrl(publicWebUrl: String?, path: String): String? {
    val baseUrl = publicWebUrl?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank) ?: return null
    return baseUrl + path
}

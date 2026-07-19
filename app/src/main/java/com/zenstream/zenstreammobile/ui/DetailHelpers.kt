package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.model.MediaItem

fun detailPlaybackTarget(item: MediaItem, episodes: List<MediaItem>): MediaItem =
    if (item.type == "Series") {
        episodes.firstOrNull { !it.played } ?: episodes.firstOrNull() ?: item
    } else item

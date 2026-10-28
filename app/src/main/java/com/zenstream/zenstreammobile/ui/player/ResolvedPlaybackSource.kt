package com.zenstream.zenstreammobile.ui.player

import com.zenstream.zenstreammobile.offline.ResolvedPlaybackSource

/** Keep ownership of [source] until the engine releases or replaces its media. */
fun PlaybackEngine.prepare(
    source: ResolvedPlaybackSource,
    startPositionSeconds: Double,
    playWhenReady: Boolean = true,
) {
    prepare(source.uri, startPositionSeconds, source.data.mimeType, playWhenReady)
}

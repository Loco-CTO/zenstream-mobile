package com.zenstream.zenstreammobile.audio

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

internal enum class AudioSourceKind {
    Hls,
    Progressive,
}

internal data class NormalizedAudioSource(
    val url: String,
    val kind: AudioSourceKind,
    val mimeType: String?,
    val mode: String?,
)

/**
 * Media3 does not reliably infer HLS from a server's vendor MIME string. Keep
 * the classification explicit so a playlist can never reach a progressive
 * extractor by accident.
 */
internal fun normalizeAudioSource(
    url: String,
    mimeType: String?,
    mode: String?,
): NormalizedAudioSource {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    val normalizedMime = mimeType?.trim()?.lowercase()?.takeIf(String::isNotBlank)
    val hlsMimeTypes =
        setOf(
            MimeTypes.APPLICATION_M3U8.lowercase(),
            "application/vnd.apple.mpegurl",
            "application/mpegurl",
        )
    val normalizedMode = mode?.trim()?.lowercase()?.takeIf(String::isNotBlank)
    val isHls =
        path.endsWith(".m3u8") ||
            normalizedMime in hlsMimeTypes ||
            normalizedMode == "audio-transcode" ||
            normalizedMode == "video-transcode"

    if (isHls) {
        return NormalizedAudioSource(
            url = url,
            kind = AudioSourceKind.Hls,
            mimeType = MimeTypes.APPLICATION_M3U8,
            mode = normalizedMode,
        )
    }

    val inferredMime =
        normalizedMime
            ?: when {
                path.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
                path.endsWith(".m4a") || path.endsWith(".aac") -> MimeTypes.AUDIO_MP4
                path.endsWith(".flac") -> MimeTypes.AUDIO_FLAC
                path.endsWith(".ogg") || path.endsWith(".opus") -> MimeTypes.AUDIO_OGG
                path.endsWith(".wav") -> MimeTypes.AUDIO_WAV
                else -> null
            }
    return NormalizedAudioSource(
        url = url,
        kind = AudioSourceKind.Progressive,
        mimeType = inferredMime,
        mode = normalizedMode,
    )
}

internal fun buildAudioMediaSource(
    dataSourceFactory: DataSource.Factory,
    mediaItem: MediaItem,
    source: NormalizedAudioSource,
): MediaSource =
    when (source.kind) {
        AudioSourceKind.Hls -> HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
        AudioSourceKind.Progressive ->
            DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem)
    }

package com.zenstream.zenstreammobile.audio

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

internal enum class AudioSourceKind {
    Hls,
    Progressive,
}

internal data class NormalizedAudioSource(
    val url: String,
    val kind: AudioSourceKind,
    val mimeType: String?,
    val mode: String?,
    val sessionId: String? = null,
    val durationSeconds: Double? = null,
    val expiresAt: String? = null,
)

/**
 * Media3 does not reliably infer HLS from a server's vendor MIME string. Keep the classification
 * explicit so a playlist can never reach a progressive extractor by accident.
 */
internal fun normalizeAudioSource(
    url: String,
    mimeType: String?,
    mode: String?,
    sessionId: String? = null,
    durationSeconds: Double? = null,
    expiresAt: String? = null,
): NormalizedAudioSource {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    val normalizedMime =
        mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotBlank)
    val hlsMimeTypes =
        setOf(
            MimeTypes.APPLICATION_M3U8.lowercase(),
            "application/vnd.apple.mpegurl",
            "application/mpegurl",
            "application/x-mpegurl",
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
            sessionId = sessionId,
            durationSeconds = durationSeconds,
            expiresAt = expiresAt,
        )
    }

    val canonicalMime = canonicalAudioMimeType(normalizedMime)
    val inferredMime =
        canonicalMime
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
        sessionId = sessionId,
        durationSeconds = durationSeconds,
        expiresAt = expiresAt,
    )
}

private fun canonicalAudioMimeType(mimeType: String?): String? =
    when (mimeType) {
        "audio/mp3",
        "audio/x-mp3",
        "audio/mpeg3" -> MimeTypes.AUDIO_MPEG
        "audio/x-m4a",
        "audio/m4a" -> MimeTypes.AUDIO_MP4
        "audio/aac",
        "audio/aacp" -> MimeTypes.AUDIO_AAC
        "audio/x-flac" -> MimeTypes.AUDIO_FLAC
        "application/ogg",
        "audio/x-ogg" -> MimeTypes.AUDIO_OGG
        "audio/x-opus" -> MimeTypes.AUDIO_OPUS
        "audio/x-wav",
        "audio/wave" -> MimeTypes.AUDIO_WAV
        else -> mimeType
    }

/**
 * The same source selection is used by the service's player and by MediaSession clients such as
 * Android Auto. In particular, never let an HLS playlist fall through to a progressive extractor.
 */
internal class AudioMediaSourceFactory(private val dataSourceFactory: DataSource.Factory) :
    MediaSource.Factory {
    private val progressiveFactory = DefaultMediaSourceFactory(dataSourceFactory)
    private val hlsFactory = HlsMediaSource.Factory(dataSourceFactory)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val configuration = mediaItem.localConfiguration
        val source =
            normalizeAudioSource(
                url = configuration?.uri?.toString().orEmpty(),
                mimeType = configuration?.mimeType,
                mode = null,
            )
        return when (source.kind) {
            AudioSourceKind.Hls -> hlsFactory.createMediaSource(mediaItem)
            AudioSourceKind.Progressive -> progressiveFactory.createMediaSource(mediaItem)
        }
    }

    override fun getSupportedTypes(): IntArray =
        intArrayOf(C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_OTHER)

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider
    ): MediaSource.Factory {
        progressiveFactory.setDrmSessionManagerProvider(provider)
        hlsFactory.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        progressiveFactory.setLoadErrorHandlingPolicy(policy)
        hlsFactory.setLoadErrorHandlingPolicy(policy)
        return this
    }
}

package com.zenstream.zenstreammobile.data

import android.media.MediaCodecList
import com.zenstream.zenstreammobile.model.PlayerEngine
import java.util.Locale

data class PlaybackCapabilities(
    val engine: String,
    val containers: List<String>,
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
    val maxAudioChannels: Int,
)

private val MEDIA3_CONTAINERS =
    listOf(
        "mp4",
        "m4v",
        "mov",
        "m4a",
        "mkv",
        "mka",
        "mk3d",
        "mks",
        "webm",
        "ts",
        "m2ts",
        "mts",
        "avi",
        "flv",
        "mpg",
        "mpeg",
        "ps",
        "vob",
        "mp3",
        "aac",
        "adts",
        "ac3",
        "eac3",
        "flac",
        "ogg",
        "oga",
        "opus",
        "wav",
    )

private val MPV_CONTAINERS =
    listOf(
        "mp4",
        "m4v",
        "mov",
        "mkv",
        "webm",
        "ts",
        "m2ts",
        "avi",
        "flv",
        "mp3",
        "m4a",
        "aac",
        "adts",
        "flac",
        "ogg",
        "oga",
        "opus",
        "wav",
        "aiff",
        "aif",
    )

private val MEDIA3_VIDEO_CODEC_MIME_TYPES =
    linkedMapOf(
        "h264" to setOf("video/avc", "video/h264"),
        "hevc" to setOf("video/hevc"),
        "vp8" to setOf("video/x-vnd.on2.vp8"),
        "vp9" to setOf("video/x-vnd.on2.vp9"),
        "av1" to setOf("video/av01"),
        "mpeg2video" to setOf("video/mpeg2"),
        "mpeg4" to setOf("video/mp4v-es", "video/divx"),
        "msmpeg4v2" to setOf("video/mp42"),
        "msmpeg4v3" to setOf("video/mp43"),
        "vc1" to setOf("video/wvc1"),
        "mjpeg" to setOf("video/mjpeg"),
    )

private val MEDIA3_AUDIO_CODEC_MIME_TYPES =
    linkedMapOf(
        "aac" to setOf("audio/mp4a-latm"),
        "mp3" to setOf("audio/mpeg"),
        "ac3" to setOf("audio/ac3"),
        "eac3" to setOf("audio/eac3", "audio/eac3-joc"),
        "opus" to setOf("audio/opus"),
        "vorbis" to setOf("audio/vorbis"),
        "flac" to setOf("audio/flac"),
        "alac" to setOf("audio/alac"),
        "dts" to setOf("audio/vnd.dts", "audio/vnd.dts.hd;profile=lbr"),
        "dts_hd" to setOf("audio/vnd.dts.hd"),
        "truehd" to setOf("audio/true-hd"),
        "mp1" to setOf("audio/mpeg-L1"),
        "mp2" to setOf("audio/mpeg-L2"),
        "amr_nb" to setOf("audio/3gpp"),
        "amr_wb" to setOf("audio/amr-wb"),
        "pcm_mulaw" to setOf("audio/g711-mlaw"),
        "pcm_alaw" to setOf("audio/g711-alaw"),
    )

private val MEDIA3_PCM_CODECS = listOf("pcm_u8", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le")

private const val FLAC_LIBRARY_CLASS = "androidx.media3.decoder.flac.FlacLibrary"

internal fun playbackCapabilities(engine: PlayerEngine): PlaybackCapabilities =
    when (engine) {
        PlayerEngine.MEDIA3 ->
            media3PlaybackCapabilities(
                decoderMimeTypes = platformDecoderMimeTypes(),
                extensionCodecs = installedMedia3ExtensionCodecs(),
            )
        PlayerEngine.MPV -> mpvPlaybackCapabilities()
    }

/** Builds the Media3 profile from decoder MIME types and formats exposed by bundled extensions. */
internal fun media3PlaybackCapabilities(
    decoderMimeTypes: Set<String>,
    extensionCodecs: Set<String> = emptySet(),
): PlaybackCapabilities {
    val availableMimeTypes = decoderMimeTypes.normalizedValues()
    val availableExtensions = extensionCodecs.normalizedValues()
    val videoCodecs =
        MEDIA3_VIDEO_CODEC_MIME_TYPES.filter { (codec, mimeTypes) ->
                codec in availableExtensions || mimeTypes.any(availableMimeTypes::contains)
            }
            .keys
            .toList()
    val audioCodecs =
        MEDIA3_AUDIO_CODEC_MIME_TYPES.filter { (codec, mimeTypes) ->
                codec in availableExtensions || mimeTypes.any(availableMimeTypes::contains)
            }
            .keys
            .toList() + MEDIA3_PCM_CODECS

    return PlaybackCapabilities(
        engine = "media3",
        containers = MEDIA3_CONTAINERS,
        videoCodecs = videoCodecs,
        audioCodecs = audioCodecs,
        maxAudioChannels = 8,
    )
}

private fun mpvPlaybackCapabilities() =
    PlaybackCapabilities(
        engine = "mpv",
        containers = MPV_CONTAINERS,
        videoCodecs =
            listOf(
                "h264",
                "h265",
                "vp8",
                "vp9",
                "av1",
                "mpeg2",
                "mpeg2video",
                "mpeg4",
                "xvid",
                "vc1",
                "theora",
            ),
        audioCodecs =
            listOf(
                "aac",
                "ac3",
                "eac3",
                "opus",
                "vorbis",
                "mp3",
                "flac",
                "alac",
                "pcm",
                "pcm_s16le",
                "pcm_s16be",
                "pcm_s24le",
                "pcm_s24be",
                "pcm_s32le",
                "pcm_s32be",
                "dts",
                "dts_hd",
                "truehd",
            ),
        maxAudioChannels = 8,
    )

private fun platformDecoderMimeTypes(): Set<String> =
    runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .codecInfos
                .asSequence()
                .filterNot { it.isEncoder }
                .flatMap { it.supportedTypes.asSequence() }
                .map { it.lowercase(Locale.ROOT) }
                .toSet()
        }
        .getOrDefault(emptySet())

private fun installedMedia3ExtensionCodecs(): Set<String> = buildSet {
    if (extensionLibraryAvailable(FLAC_LIBRARY_CLASS)) add("flac")
    (MEDIA3_VIDEO_CODEC_MIME_TYPES + MEDIA3_AUDIO_CODEC_MIME_TYPES).forEach { (codec, mimeTypes) ->
        if (mimeTypes.any(Media3NativeDecoders::supports)) add(codec)
    }
}

private fun extensionLibraryAvailable(className: String): Boolean =
    runCatching {
            Class.forName(className).getMethod("isAvailable").invoke(null) as? Boolean == true
        }
        .getOrDefault(false)

private fun Set<String>.normalizedValues(): Set<String> =
    mapTo(mutableSetOf()) { it.trim().lowercase(Locale.ROOT) }

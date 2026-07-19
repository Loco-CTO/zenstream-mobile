package com.zenstream.zenstreammobile.model

enum class PlayerEngine {
    MEDIA3,
    MPV,
}

enum class PlaybackSegmentType {
    INTRO,
    OUTRO,
}

data class PlaybackSegment(
    val type: PlaybackSegmentType,
    val startSeconds: Double,
    val endSeconds: Double,
)

data class MediaStream(
    val index: Int,
    val type: String,
    val displayTitle: String? = null,
    val language: String? = null,
    val isDefault: Boolean = false,
)

data class MediaSource(
    val id: String?,
    val directStreamUrl: String? = null,
    val transcodingUrl: String? = null,
    val mediaStreams: List<MediaStream> = emptyList(),
    val runTimeTicks: Long? = null,
)

data class PlaybackData(
    val item: MediaItem,
    val source: MediaSource,
    val audio: List<MediaStream>,
    val subtitles: List<MediaStream>,
    val segments: List<PlaybackSegment> = emptyList(),
    val qualities: List<Int> = listOf(0, 1_000_000, 2_000_000, 4_000_000, 8_000_000, 16_000_000, 32_000_000, 64_000_000),
)

data class PlaybackOptions(
    val maxStreamingBitrate: Int? = null,
    val startTimeTicks: Long = 0L,
    val mediaSourceId: String? = null,
    val audioStreamIndex: Int? = null,
    val forceTranscoding: Boolean = false,
    val directPlayOnly: Boolean = false,
)

data class SubtitleStyle(
    val fontFamily: String = "sans",
    val bold: Boolean = false,
    val textScale: Float = 100f,
    val fontColor: String = "#ffffff",
    val borderSize: Float = 0f,
    val borderColor: String = "#000000",
    val backgroundColor: String = "#000000",
    val backgroundOpacity: Float = 0f,
)

data class SubtitleCue(
    val startSeconds: Double,
    val endSeconds: Double,
    val text: String,
)

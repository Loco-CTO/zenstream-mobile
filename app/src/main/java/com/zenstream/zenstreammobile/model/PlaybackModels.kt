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
    val isLyrics: Boolean = false,
    val codec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val channels: Int? = null,
)

data class TrickplaySheet(
    val index: Int,
    val frameCount: Int,
    val url: String,
)

data class TrickplayManifest(
    val state: String,
    val sourceId: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val intervalSeconds: Double,
    val columns: Int,
    val rows: Int,
    val frameCount: Int,
    val sheets: List<TrickplaySheet>,
)

data class MediaSource(
    val id: String?,
    val url: String? = null,
    val mediaStreams: List<MediaStream> = emptyList(),
    val durationSeconds: Double? = null,
    val trickplay: TrickplayManifest? = null,
    val container: String? = null,
    val transcodingContainer: String? = null,
    val bitrate: Int? = null,
    val viewerSessionId: String? = null,
)

data class ViewerCommand(
    val id: String,
    val action: String,
    val issuedAt: String? = null,
)

data class ViewerCommandAck(
    val id: String,
    val success: Boolean,
    val error: String? = null,
)

data class ViewerHeartbeat(val commands: List<ViewerCommand> = emptyList())

data class ViewerEnd(
    val workerSessionId: String? = null,
    val stopWorker: Boolean = false,
)

data class TrickplayPreview(
    val url: String,
    val width: Int,
    val height: Int,
    val tileIndex: Int,
    val cellX: Int,
    val cellY: Int,
    val columns: Int,
    val rows: Int,
)

data class PlaybackData(
    val item: MediaItem,
    val source: MediaSource,
    val audioTracks: List<MediaStream>,
    val subtitles: List<MediaStream>,
    val segments: List<PlaybackSegment> = emptyList(),
    val qualities: List<Int> =
        listOf(
            0,
            1_000_000,
            2_000_000,
            4_000_000,
            8_000_000,
            16_000_000,
            32_000_000,
            64_000_000,
        ),
    val mode: String? = null,
    val sessionState: String? = null,
    val sessionId: String? = null,
    val viewerSessionId: String? = null,
    val url: String? = null,
    val durationSeconds: Double? = null,
    val startPositionSeconds: Double = 0.0,
    val expiresAt: String? = null,
    val errorCode: String? = null,
    val errorDetail: String? = null,
)

data class PlaybackSessionStatus(
    val sessionId: String,
    val sessionState: String,
    val playlistReady: Boolean = false,
    val segmentCount: Int = 0,
    val processAlive: Boolean = false,
    val errorCode: String? = null,
    val errorDetail: String? = null,
    val lastAccessedAt: String? = null,
)

data class PlaybackOptions(
    val engine: PlayerEngine = PlayerEngine.MEDIA3,
    val maxStreamingBitrate: Int? = null,
    val startPositionSeconds: Double = 0.0,
    val sourceId: String? = null,
    val audioStreamId: Int? = null,
    val forceTranscoding: Boolean = false,
    val directPlayOnly: Boolean = false,
    val requestedMode: String? = null,
)

data class PlaybackTrackSelection(
    val audioStreamId: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val hasSubtitleSelection: Boolean = false,
)

data class SubtitleStyle(
    val fontFamily: String = "sans",
    val bold: Boolean = false,
    val textScale: Float = 100f,
    val fontColor: String = "#ffffff",
    val borderSize: Float = 2f,
    val borderColor: String = "#000000",
    val backgroundColor: String = "#000000",
    val backgroundOpacity: Float = 0f,
)

data class SubtitleCue(
    val startSeconds: Double,
    val endSeconds: Double,
    val text: String,
)

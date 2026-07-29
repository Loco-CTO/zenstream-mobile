package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.PlayerEngine

data class PlaybackCapabilities(
    val engine: String,
    val containers: List<String>,
    val videoCodecs: List<String>,
    val audioCodecs: List<String>,
    val maxAudioChannels: Int,
)

internal fun playbackCapabilities(engine: PlayerEngine): PlaybackCapabilities = when (engine) {
    PlayerEngine.MEDIA3 -> PlaybackCapabilities(
        engine = "media3",
        containers = listOf("mp4", "m4v", "mov", "mkv", "webm", "ts", "m2ts", "avi", "flv", "ogg"),
        videoCodecs = listOf("h264", "h265", "vp8", "vp9", "av1", "mpeg2", "mpeg4"),
        audioCodecs = listOf("aac", "ac3", "eac3", "opus", "vorbis", "mp3", "flac", "alac", "pcm"),
        maxAudioChannels = 8,
    )
    PlayerEngine.MPV -> PlaybackCapabilities(
        engine = "mpv",
        containers = listOf("mp4", "m4v", "mov", "mkv", "webm", "ts", "m2ts", "avi", "flv", "ogg"),
        videoCodecs = listOf("h264", "h265", "vp8", "vp9", "av1", "mpeg2", "mpeg4", "vc1", "theora"),
        audioCodecs = listOf("aac", "ac3", "eac3", "opus", "vorbis", "mp3", "flac", "alac", "pcm", "dts", "truehd"),
        maxAudioChannels = 8,
    )
}

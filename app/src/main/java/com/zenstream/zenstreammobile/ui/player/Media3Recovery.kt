package com.zenstream.zenstreammobile.ui.player

enum class VideoDecoderPreference {
    AUTO,
    HARDWARE,
    ANDROID_SOFTWARE,
    APP_SOFTWARE,
}

enum class Media3FailureKind {
    DECODER,
    CONTAINER,
    NETWORK,
    AUTHENTICATION,
    HDR_UNSUPPORTED,
    OTHER,
}

data class Media3PlaybackDetails(
    val preference: VideoDecoderPreference = VideoDecoderPreference.AUTO,
    val videoDecoder: String? = null,
    val audioDecoder: String? = null,
    val videoFormat: String? = null,
    val firstFrameRendered: Boolean = false,
    val videoSoftwareRetries: Int = 0,
    val audioSoftwareRetries: Int = 0,
    val recoveryStage: String? = null,
    val failureKind: Media3FailureKind? = null,
    val failureSequence: Long = 0,
    val hdr: Boolean = false,
    val playWhenReady: Boolean = true,
)

data class Media3SourceContext(
    val identity: String,
    val expectedVideo: Boolean,
    val hdr: Boolean = false,
    val mode: String? = null,
    val audioStreamId: Int? = null,
)

interface Media3DecoderControl {
    fun configureSource(context: Media3SourceContext)

    fun selectVideoDecoder(preference: VideoDecoderPreference)
}

/** Attempt state belongs to a source, not a prepare/error callback. */
internal class Media3LocalRecovery {
    private var videoAttempted = false
    private var audioAttempted = false

    fun reset() {
        videoAttempted = false
        audioAttempted = false
    }

    fun resetAudio() {
        audioAttempted = false
    }

    fun resetVideo() {
        videoAttempted = false
    }

    fun trySoftware(
        video: Boolean,
        available: Boolean,
        alreadySoftware: Boolean,
        automaticVideo: Boolean,
        hdr: Boolean,
    ): Boolean {
        if (!available || alreadySoftware || (video && (!automaticVideo || hdr))) return false
        if (video) {
            if (videoAttempted) return false
            videoAttempted = true
        } else {
            if (audioAttempted) return false
            audioAttempted = true
        }
        return true
    }
}

/** Server recovery only advances: direct/remux -> audio conversion -> video conversion. */
internal class Media3ServerRecovery {
    private var sourceIdentity: String? = null
    private val attempted = mutableSetOf<String>()

    fun nextMode(
        identity: String,
        currentMode: String?,
        audioFailure: Boolean,
        kind: Media3FailureKind,
        hdr: Boolean,
    ): String? {
        if (sourceIdentity != identity) {
            sourceIdentity = identity
            attempted.clear()
        }
        if (kind !in setOf(Media3FailureKind.DECODER, Media3FailureKind.CONTAINER)) return null
        val next =
            when {
                currentMode == "video-transcode" -> null
                audioFailure && currentMode == "audio-transcode" -> null
                audioFailure -> "audio-transcode"
                hdr -> null
                else -> "video-transcode"
            } ?: return null
        return next.takeIf { attempted.add(it) }
    }
}

/** Timeouts count only periods in which playback can actually produce video. */
internal class VideoFrameWatchdog {
    private var eligibleSince: Long? = null
    private var lastFrameAt: Long? = null
    private var initialPosition = 0L
    private var initialInputs = 0
    private var lastRendered = 0
    private var detected = false

    fun reset() {
        eligibleSince = null
        lastFrameAt = null
        detected = false
    }

    fun sample(
        nowMillis: Long,
        eligible: Boolean,
        positionMillis: Long,
        queuedInputs: Int,
        renderedFrames: Int,
        firstFrame: Boolean,
    ): Boolean {
        if (detected) return false
        if (!eligible) {
            eligibleSince = null
            lastFrameAt = null
            return false
        }
        if (eligibleSince == null) {
            eligibleSince = nowMillis
            lastFrameAt = nowMillis
            initialPosition = positionMillis
            initialInputs = queuedInputs
            lastRendered = renderedFrames
            return false
        }
        if (renderedFrames != lastRendered) {
            lastRendered = renderedFrames
            lastFrameAt = nowMillis
            initialInputs = queuedInputs
        }
        val progressing = positionMillis - initialPosition >= 1_000
        val timedOut =
            if (!firstFrame) nowMillis - requireNotNull(eligibleSince) >= 8_000
            else nowMillis - requireNotNull(lastFrameAt) >= 10_000 && queuedInputs > initialInputs
        if (progressing && timedOut) {
            detected = true
            return true
        }
        return false
    }
}

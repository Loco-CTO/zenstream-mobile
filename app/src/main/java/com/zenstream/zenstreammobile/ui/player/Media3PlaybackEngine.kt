package com.zenstream.zenstreammobile.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.zenstream.zenstreammobile.data.Media3NativeDecoders
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@androidx.annotation.OptIn(UnstableApi::class)
class Media3PlaybackEngine(
    private val platformDecoderSelector: MediaCodecSelector = MediaCodecSelector.DEFAULT
) : PlaybackEngine, Media3DecoderControl {
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(EngineState(media3 = Media3PlaybackDetails()))
    override val state: StateFlow<EngineState> = mutableState
    private var player: ExoPlayer? = null
    private var selector: DefaultTrackSelector? = null
    private var manager: DecoderManager? = null
    private var context = Media3SourceContext("", false)
    private var preference = VideoDecoderPreference.AUTO
    private val attempts = Media3LocalRecovery()
    private val watchdog = VideoFrameWatchdog()
    private val initialSeek = InitialSeekController()
    private var counters: DecoderCounters? = null
    private var pending: (() -> Unit)? = null
    private var surface: SurfaceView? = null
    private var surfaceReady = false
    private var graceUntil = 0L
    private var generation = 0L
    private var failureSequence = 0L
    private var terminalFailure = false
    private var released = false

    private fun details(update: (Media3PlaybackDetails) -> Media3PlaybackDetails) {
        mutableState.value =
            mutableState.value.copy(
                media3 = update(mutableState.value.media3 ?: Media3PlaybackDetails())
            )
    }

    private val surfaceCallback =
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = holder.surface.isValid
                restartWatchdog()
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int,
            ) {
                surfaceReady = holder.surface.isValid && width > 0 && height > 0
                restartWatchdog()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                restartWatchdog()
            }
        }

    private val analytics =
        object : AnalyticsListener {
            override fun onVideoEnabled(
                eventTime: AnalyticsListener.EventTime,
                decoderCounters: DecoderCounters,
            ) {
                counters = decoderCounters
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                details { it.copy(videoDecoder = decoderName) }
                Log.i(TAG, "video decoder=$decoderName initMs=$initializationDurationMs")
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                details { it.copy(audioDecoder = decoderName) }
                Log.i(TAG, "audio decoder=$decoderName initMs=$initializationDurationMs")
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                details {
                    it.copy(
                        videoFormat =
                            "${format.sampleMimeType} ${format.width}Ã—${format.height} ${format.codecs.orEmpty()}",
                        hdr =
                            context.hdr ||
                                ColorInfo.isTransferHdr(format.colorInfo) ||
                                format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION,
                    )
                }
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                details { it.copy(firstFrameRendered = true, recoveryStage = null) }
                Log.i(TAG, "first video frame rendered")
            }
        }

    private val listener =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val current = player ?: return
                val renderer = error as? ExoPlaybackException
                val track =
                    renderer?.let {
                        media3PlaybackErrorTrackType(
                            it.type,
                            it.rendererIndex,
                            current::getRendererType,
                        )
                    } ?: C.TRACK_TYPE_VIDEO
                Log.w(TAG, "playback failure code=${error.errorCodeName} track=$track")
                fail(classifyMedia3Failure(error.errorCode), track)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!terminalFailure)
                    mutableState.value =
                        media3PlaybackState(mutableState.value, playbackState).let {
                            it.copy(
                                ready =
                                    it.ready &&
                                        (!context.expectedVideo ||
                                            it.media3?.firstFrameRendered == true)
                            )
                        }
                if (playbackState == Player.STATE_READY) {
                    initialSeek.consume()?.let { player?.seekTo((it * 1000).toLong()) }
                    inspectTracksLater()
                }
            }

            override fun onTracksChanged(tracks: Tracks) = inspectTracksLater()
        }

    private val ticker =
        object : Runnable {
            override fun run() {
                if (released) return
                player?.let { current ->
                    val old = mutableState.value
                    val videoReady =
                        !context.expectedVideo || old.media3?.firstFrameRendered == true
                    mutableState.value =
                        old.copy(
                            positionSeconds = current.currentPosition.coerceAtLeast(0) / 1000.0,
                            durationSeconds =
                                current.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0,
                            bufferedSeconds = current.bufferedPosition.coerceAtLeast(0) / 1000.0,
                            isPlaying = current.isPlaying && !terminalFailure && videoReady,
                            isBuffering =
                                (current.playbackState == Player.STATE_BUFFERING ||
                                    (current.playbackState == Player.STATE_READY && !videoReady)) &&
                                    !terminalFailure,
                            ready =
                                current.playbackState == Player.STATE_READY &&
                                    !terminalFailure &&
                                    videoReady,
                            speed = current.playbackParameters.speed,
                        )
                    counters?.ensureUpdated()
                    val now = SystemClock.elapsedRealtime()
                    if (
                        watchdog.sample(
                            nowMillis = now,
                            eligible =
                                context.expectedVideo &&
                                    !terminalFailure &&
                                    surfaceReady &&
                                    now >= graceUntil &&
                                    current.isPlaying &&
                                    current.playbackState == Player.STATE_READY,
                            positionMillis = current.currentPosition,
                            queuedInputs = counters?.queuedInputBufferCount ?: 0,
                            renderedFrames = counters?.renderedOutputBufferCount ?: 0,
                            firstFrame = mutableState.value.media3?.firstFrameRendered == true,
                        )
                    ) {
                        fail(Media3FailureKind.DECODER, C.TRACK_TYPE_VIDEO)
                    }
                }
                handler.postDelayed(this, 250L)
            }
        }

    override fun configureSource(context: Media3SourceContext) {
        if (this.context.identity != context.identity) {
            attempts.reset()
            preference = VideoDecoderPreference.AUTO
        } else if (this.context.audioStreamId != context.audioStreamId) {
            attempts.resetAudio()
            manager?.selectAudioDecoder(DecoderMode.AUTO)
        }
        if (this.context.mode != context.mode || this.context.identity != context.identity) {
            manager?.selectVideoDecoder(preference.toMode())
            manager?.selectAudioDecoder(DecoderMode.AUTO)
        }
        this.context = context
    }

    override fun selectVideoDecoder(preference: VideoDecoderPreference) {
        this.preference = preference
        attempts.resetVideo()
        details { it.copy(preference = preference) }
        val current = player ?: return
        if (preference != VideoDecoderPreference.AUTO && manager == null) {
            fail(Media3FailureKind.DECODER, C.TRACK_TYPE_VIDEO)
            return
        }
        terminalFailure = false
        mutableState.value = mutableState.value.copy(error = null, errorTrackType = null)
        manager?.selectVideoDecoder(preference.toMode())
        if (current.playerError != null) current.prepare()
        restartWatchdog()
        inspectTracksLater()
    }

    override fun createView(context: Context): View {
        check(!released)
        if (player == null) {
            selector = DefaultTrackSelector(context.applicationContext)
            val factory =
                if (Media3NativeDecoders.available) {
                    val decoderManager = DecoderManager()
                    manager = decoderManager
                    NextRenderersFactory(context.applicationContext)
                        .setDecoderManager(decoderManager)
                } else {
                    DefaultRenderersFactory(context.applicationContext)
                        .setEnableDecoderFallback(true)
                        .setExtensionRendererMode(
                            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                        )
                }
            factory.setMediaCodecSelector(platformDecoderSelector)
            player =
                ExoPlayer.Builder(context.applicationContext, factory)
                    .setTrackSelector(requireNotNull(selector))
                    .build()
                    .also { current ->
                        current.trackSelectionParameters =
                            current.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                        manager?.attach(current)
                        current.addListener(listener)
                        current.addAnalyticsListener(analytics)
                    }
            handler.post(ticker)
        }
        return PlayerView(context).apply {
            useController = false
            subtitleView?.visibility = View.GONE
            player = this@Media3PlaybackEngine.player
            surface?.holder?.removeCallback(surfaceCallback)
            surface = videoSurfaceView as? SurfaceView
            surface?.holder?.addCallback(surfaceCallback)
            surfaceReady = surface?.holder?.surface?.isValid == true && (surface?.width ?: 0) > 0
            restartWatchdog()
            pending?.also { pending = null }?.invoke()
        }
    }

    override fun currentPositionSeconds(): Double =
        player?.currentPosition?.coerceAtLeast(0)?.div(1000.0) ?: mutableState.value.positionSeconds

    override fun prepare(
        url: String,
        startPositionSeconds: Double,
        mimeType: String?,
        playWhenReady: Boolean,
    ) {
        if (released) return
        val current =
            player
                ?: run {
                    pending = { prepare(url, startPositionSeconds, mimeType, playWhenReady) }
                    return
                }
        generation++
        terminalFailure = false
        mutableState.value =
            EngineState(media3 = Media3PlaybackDetails(preference = preference, hdr = context.hdr))
        counters = null
        restartWatchdog()
        initialSeek.schedule(startPositionSeconds)
        current.playWhenReady = playWhenReady
        current.setMediaItem(
            MediaItem.Builder().setUri(url).apply { mimeType?.let(::setMimeType) }.build(),
            (startPositionSeconds.coerceAtLeast(0.0) * 1000).toLong(),
        )
        current.prepare()
    }

    private fun inspectTracksLater() {
        val requestedGeneration = generation
        handler.post {
            val current = player ?: return@post
            if (released || terminalFailure || requestedGeneration != generation) return@post
            val tracks = current.currentTracks
            if (tracks.groups.isEmpty() && current.playbackState != Player.STATE_READY) return@post
            for (type in listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO)) {
                val groups = tracks.groups.filter { it.type == type }
                if (
                    type == C.TRACK_TYPE_VIDEO &&
                        groups.any { group ->
                            (0 until group.length).any { index ->
                                val format = group.getTrackFormat(index)
                                ColorInfo.isTransferHdr(format.colorInfo) ||
                                    format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
                            }
                        }
                )
                    details { it.copy(hdr = true) }
                val unmapped = selector?.currentMappedTrackInfo?.unmappedTrackGroups
                val hasUnmapped =
                    unmapped != null && (0 until unmapped.length).any { unmapped[it].type == type }
                if ((groups.isNotEmpty() || hasUnmapped) && groups.none { it.isSupported(true) }) {
                    fail(Media3FailureKind.DECODER, type)
                    return@post
                }
                if (
                    type == C.TRACK_TYPE_VIDEO &&
                        context.expectedVideo &&
                        groups.isEmpty() &&
                        !hasUnmapped &&
                        current.playbackState == Player.STATE_READY
                ) {
                    fail(Media3FailureKind.CONTAINER, type)
                    return@post
                }
            }
        }
    }

    private fun fail(kind: Media3FailureKind, track: Int) {
        if (terminalFailure || released) return
        val current = player ?: return
        val video = track != C.TRACK_TYPE_AUDIO
        val hdr = context.hdr || mutableState.value.media3?.hdr == true
        val active = if (video) manager?.activeVideoMode else manager?.activeAudioMode
        val requested = if (video) manager?.videoMode else manager?.audioMode
        if (
            kind == Media3FailureKind.DECODER &&
                attempts.trySoftware(
                    video = video,
                    available = manager != null,
                    alreadySoftware =
                        active == DecoderMode.FFMPEG || requested == DecoderMode.FFMPEG,
                    automaticVideo = preference == VideoDecoderPreference.AUTO,
                    hdr = hdr,
                )
        ) {
            val position = current.currentPosition
            val intent = current.playWhenReady
            details {
                it.copy(
                    recoveryStage = if (video) "app software video" else "app software audio",
                    videoSoftwareRetries = it.videoSoftwareRetries + if (video) 1 else 0,
                    audioSoftwareRetries = it.audioSoftwareRetries + if (video) 0 else 1,
                    firstFrameRendered = if (video) false else it.firstFrameRendered,
                )
            }
            Log.i(TAG, "local software recovery track=$track")
            restartWatchdog()
            if (video) manager?.selectVideoDecoder(DecoderMode.FFMPEG)
            else manager?.selectAudioDecoder(DecoderMode.FFMPEG)
            current.playWhenReady = intent
            if (current.playerError != null) {
                current.prepare()
                current.seekTo(position)
            }
            inspectTracksLater()
            return
        }
        terminalFailure = true
        failureSequence++
        val finalKind =
            if (
                video &&
                    hdr &&
                    kind in setOf(Media3FailureKind.DECODER, Media3FailureKind.CONTAINER)
            )
                Media3FailureKind.HDR_UNSUPPORTED
            else kind
        mutableState.value =
            mutableState.value.copy(
                ready = false,
                isPlaying = false,
                isBuffering = false,
                error =
                    if (finalKind == Media3FailureKind.HDR_UNSUPPORTED)
                        "This HDR video cannot be rendered by the selected decoder."
                    else
                        "Media3 could not play the ${if (video) "video" else "audio"} track ($finalKind).",
                errorTrackType = track,
                media3 =
                    mutableState.value.media3?.copy(
                        failureKind = finalKind,
                        failureSequence = failureSequence,
                        hdr = hdr,
                        playWhenReady = current.playWhenReady,
                    ),
            )
        current.pause()
    }

    private fun restartWatchdog() {
        watchdog.reset()
        graceUntil = SystemClock.elapsedRealtime() + 2_000
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionSeconds: Double) {
        initialSeek.cancel()
        restartWatchdog()
        player?.seekTo((positionSeconds.coerceAtLeast(0.0) * 1000).toLong())
    }

    override fun setSpeed(value: Float) {
        player?.setPlaybackSpeed(value.coerceIn(.25f, 3f))
    }

    override fun release() {
        released = true
        generation++
        handler.removeCallbacksAndMessages(null)
        surface?.holder?.removeCallback(surfaceCallback)
        manager?.detach()
        player?.release()
        player = null
        pending = null
    }

    private fun VideoDecoderPreference.toMode(): DecoderMode =
        when (this) {
            VideoDecoderPreference.AUTO -> DecoderMode.AUTO
            VideoDecoderPreference.HARDWARE -> DecoderMode.HARDWARE
            VideoDecoderPreference.ANDROID_SOFTWARE -> DecoderMode.SOFTWARE
            VideoDecoderPreference.APP_SOFTWARE -> DecoderMode.FFMPEG
        }

    companion object {
        private const val TAG = "ZenStreamMedia3"
    }
}

internal fun classifyMedia3Failure(code: Int): Media3FailureKind =
    when (code) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> Media3FailureKind.DECODER
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> Media3FailureKind.CONTAINER
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> Media3FailureKind.AUTHENTICATION
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> Media3FailureKind.NETWORK
        else -> Media3FailureKind.OTHER
    }

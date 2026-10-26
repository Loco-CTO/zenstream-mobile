package com.zenstream.zenstreammobile.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.zenstream.zenstreammobile.model.MpvPlaybackSettings
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.offline.OfflineMediaDataSource
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVLib
import java.io.File
import java.net.URI
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal val mpvCaptionOptions =
    mapOf(
        "sub-auto" to "no",
        "sid" to "no",
        "secondary-sid" to "no",
    )

internal fun mpvVideoRenderingOptions(settings: MpvPlaybackSettings = MpvPlaybackSettings()) =
    listOf(
        "profile" to settings.profile.storageValue,
        "scale" to settings.scaler.storageValue,
        "cscale" to settings.scaler.storageValue,
    )

internal class MpvSurfaceLifecycle {
    private var surfaceReady = false
    private var destroyRequested = false
    private var destroyed = false

    fun canUseSurface(): Boolean = !destroyed

    fun hasSurface(): Boolean = surfaceReady

    fun markSurfaceCreated() {
        if (!destroyed) surfaceReady = true
    }

    fun requestDestroy(): Boolean {
        if (destroyed) return false
        destroyRequested = true
        return !surfaceReady
    }

    fun markSurfaceDestroyed(): Boolean {
        if (destroyed) return false
        surfaceReady = false
        return destroyRequested
    }

    fun markDestroyed(): Boolean {
        if (destroyed) return false
        destroyed = true
        return true
    }
}

internal fun mpvPlaybackPath(source: String): String =
    if (source.startsWith("file:", ignoreCase = true))
        runCatching { File(URI(source)).absolutePath }.getOrDefault(source)
    else source

data class EngineState(
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val bufferedSeconds: Double = 0.0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val speed: Float = 1f,
    val ready: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
    val errorTrackType: Int? = null,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun media3PlaybackErrorTrackType(
    errorType: Int?,
    rendererIndex: Int,
    rendererTypeAt: (Int) -> Int,
): Int? {
    if (errorType != ExoPlaybackException.TYPE_RENDERER || rendererIndex < 0) return null
    return runCatching { rendererTypeAt(rendererIndex) }
        .getOrNull()
        ?.takeIf { it == C.TRACK_TYPE_AUDIO || it == C.TRACK_TYPE_VIDEO }
}

internal fun playbackRecoveryMode(errorTrackType: Int?): String =
    if (errorTrackType == C.TRACK_TYPE_AUDIO) "audio-transcode" else "video-transcode"

internal fun media3PlaybackState(state: EngineState, playbackState: Int): EngineState {
    val activePlayback =
        playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY
    return state.copy(
        ready = playbackState == Player.STATE_READY,
        isBuffering = playbackState == Player.STATE_BUFFERING,
        ended = playbackState == Player.STATE_ENDED,
        error = if (activePlayback) null else state.error,
        errorTrackType = if (activePlayback) null else state.errorTrackType,
    )
}

internal class InitialSeekController {
    private var pendingPositionSeconds: Double? = null

    fun schedule(positionSeconds: Double) {
        pendingPositionSeconds = positionSeconds.takeIf { it.isFinite() && it > 0.0 }
    }

    fun consume(): Double? = pendingPositionSeconds.also { pendingPositionSeconds = null }

    fun cancel() {
        pendingPositionSeconds = null
    }
}

internal fun mpvPlaybackEnded(
    state: EngineState,
    sourceReady: Boolean,
    eofReached: Boolean?,
): Boolean = state.ended || (sourceReady && eofReached == true)

internal class MpvEndFileGate {
    private var sourceLoaded = false
    private var ignoreReplacementEnd = false

    fun onSourceLoading() {
        if (sourceLoaded) ignoreReplacementEnd = true
        sourceLoaded = true
    }

    fun onSourceReady() {
        ignoreReplacementEnd = false
    }

    fun shouldReportEndFile(eofReached: Boolean? = null): Boolean {
        if (eofReached != null) {
            ignoreReplacementEnd = false
            return eofReached
        }
        if (ignoreReplacementEnd) {
            ignoreReplacementEnd = false
            return false
        }
        return true
    }

    fun clear() {
        sourceLoaded = false
        ignoreReplacementEnd = false
    }
}

private data class PendingPlayback(
    val url: String,
    val startPositionSeconds: Double,
    val mimeType: String?,
    val playWhenReady: Boolean,
    val audioOrdinal: Int? = null,
)

interface PlaybackEngine {
    val state: StateFlow<EngineState>

    fun createView(context: Context): View

    fun currentPositionSeconds(): Double = state.value.positionSeconds

    /** Loads the source, optionally waiting for an authoritative caller to start it. */
    fun prepare(
        url: String,
        startPositionSeconds: Double,
        mimeType: String? = null,
        playWhenReady: Boolean = true,
    )

    fun play()

    fun pause()

    fun stop() = pause()

    fun seekTo(positionSeconds: Double)

    fun setSpeed(value: Float)

    fun selectAudioTrack(ordinal: Int) = Unit

    fun setKnownHdr(value: Boolean) = Unit

    fun recoverLocal(trackType: Int?): Boolean = false

    fun release()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3PlaybackEngine : PlaybackEngine {
    private val tag = "ZenStreamPlayback"
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var pending: PendingPlayback? = null
    private var lastRequest: PendingPlayback? = null
    private var lastContext: Context? = null
    private var knownHdr = false
    private val localRecovery = LocalDecoderRecovery()

    override fun setKnownHdr(value: Boolean) {
        knownHdr = value
    }

    override fun recoverLocal(trackType: Int?): Boolean {
        val request = lastRequest?.takeIf { it.url.startsWith("file:") } ?: return false
        val context = lastContext ?: return false
        val hdr =
            knownHdr ||
                player?.currentTracks?.groups.orEmpty().any { group ->
                    (0 until group.length).any {
                        group.getTrackFormat(it).colorInfo?.colorTransfer in
                            setOf(C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG)
                    }
                }
        if (!localRecovery.attempt(trackType, hdr)) return false
        val position = currentPositionSeconds()
        handler.removeCallbacks(ticker)
        player?.release()
        player = null
        pending = request.copy(startPositionSeconds = position, audioOrdinal = audioOrdinal)
        createView(context)
        return true
    }

    private var audioOrdinal: Int? = null

    private fun applyAudioSelection() {
        val current = player ?: return
        val target =
            current.currentTracks.groups
                .filter { it.type == C.TRACK_TYPE_AUDIO }
                .flatMap { group -> (0 until group.length).map { track -> group to track } }
                .getOrNull(audioOrdinal ?: return) ?: return
        if (target.first.isTrackSelected(target.second)) return
        current.trackSelectionParameters =
            current.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    TrackSelectionOverride(target.first.mediaTrackGroup, target.second)
                )
                .build()
    }

    override fun selectAudioTrack(ordinal: Int) {
        require(ordinal >= 0)
        audioOrdinal = ordinal
        pending = pending?.copy(audioOrdinal = ordinal)
        applyAudioSelection()
    }

    private val initialSeek = InitialSeekController()
    private val ticker =
        object : Runnable {
            override fun run() {
                val current = player
                if (current != null) {
                    val duration = current.duration.takeIf { it > 0 } ?: 0L
                    _state.value =
                        _state.value.copy(
                            positionSeconds = current.currentPosition / 1000.0,
                            durationSeconds = duration / 1000.0,
                            bufferedSeconds = current.bufferedPosition / 1000.0,
                            isPlaying = current.isPlaying,
                            isBuffering = current.playbackState == Player.STATE_BUFFERING,
                            speed = current.playbackParameters.speed,
                            ready = current.playbackState == Player.STATE_READY,
                        )
                }
                handler.postDelayed(this, 250L)
            }
        }

    override fun createView(context: Context): View {
        lastContext = context.applicationContext
        if (player == null) {
            player =
                ExoPlayer.Builder(
                        context.applicationContext,
                        DefaultRenderersFactory(context.applicationContext)
                            .setEnableDecoderFallback(true)
                            .setMediaCodecSelector { mime, secure, tunnel ->
                                MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunnel)
                                    .filter { !localRecovery.softwareFor(mime) || it.softwareOnly }
                            }
                            .setExtensionRendererMode(
                                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                            ),
                    )
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(
                            androidx.media3.datasource.DataSource.Factory {
                                OfflineMediaDataSource(
                                    DefaultDataSource.Factory(context.applicationContext)
                                        .createDataSource()
                                )
                            }
                        )
                    )
                    .build()
                    .also { exo ->
                        exo.trackSelectionParameters =
                            exo.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                        exo.addListener(
                            object : Player.Listener {
                                override fun onTracksChanged(tracks: Tracks) {
                                    applyAudioSelection()
                                }

                                override fun onPlayerError(error: PlaybackException) {
                                    val rendererError = error as? ExoPlaybackException
                                    Log.e(
                                        tag,
                                        "Media3 playback error code=${error.errorCodeName} message=${error.message}",
                                    )
                                    _state.value =
                                        _state.value.copy(
                                            error = error.message ?: "Playback failed",
                                            errorTrackType =
                                                rendererError?.let {
                                                    media3PlaybackErrorTrackType(
                                                        errorType = it.type,
                                                        rendererIndex = it.rendererIndex,
                                                        rendererTypeAt = exo::getRendererType,
                                                    )
                                                },
                                        )
                                }

                                override fun onPlaybackStateChanged(playbackState: Int) {
                                    _state.value = media3PlaybackState(_state.value, playbackState)
                                    Log.i(
                                        tag,
                                        "Media3 playback state=$playbackState ready=${playbackState == Player.STATE_READY} buffering=${playbackState == Player.STATE_BUFFERING}",
                                    )
                                    if (playbackState == Player.STATE_READY) applyInitialSeek()
                                }
                            }
                        )
                    }
            handler.post(ticker)
        }
        return (playerView
                ?: PlayerView(context)
                    .apply {
                        useController = false
                        subtitleView?.visibility = View.GONE
                    }
                    .also { playerView = it })
            .apply { player = this@Media3PlaybackEngine.player }
            .also {
                pending?.let { request ->
                    prepare(
                        request.url,
                        request.startPositionSeconds,
                        request.mimeType,
                        request.playWhenReady,
                    )
                    request.audioOrdinal?.let(::selectAudioTrack)
                }
            }
    }

    override fun currentPositionSeconds(): Double =
        player?.currentPosition?.coerceAtLeast(0L)?.div(1000.0) ?: _state.value.positionSeconds

    override fun prepare(
        url: String,
        startPositionSeconds: Double,
        mimeType: String?,
        playWhenReady: Boolean,
    ) {
        Log.i(
            tag,
            "Media3 prepare url=${redactPlaybackUrl(url)} mimeType=$mimeType start=$startPositionSeconds playWhenReady=$playWhenReady",
        )
        val current = player
        lastRequest = PendingPlayback(url, startPositionSeconds, mimeType, playWhenReady)
        localRecovery.source(url)
        audioOrdinal = null
        if (current == null) {
            pending =
                PendingPlayback(
                    url,
                    startPositionSeconds,
                    mimeType,
                    playWhenReady,
                )
            return
        }
        pending = null
        current.trackSelectionParameters =
            current.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .build()
        _state.value = EngineState()
        initialSeek.schedule(startPositionSeconds)
        current.playWhenReady = playWhenReady
        val mediaItem =
            MediaItem.Builder()
                .setUri(url)
                .apply {
                    mimeType?.let { setMimeType(it) }
                }
                .build()
        current.setMediaItem(mediaItem)
        current.prepare()
    }

    private fun applyInitialSeek() {
        val positionSeconds = initialSeek.consume() ?: return
        player?.seekTo((positionSeconds * 1000).toLong())
    }

    override fun play() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun stop() {
        pending = null
        player?.stop()
        player?.clearMediaItems()
    }

    override fun seekTo(positionSeconds: Double) {
        initialSeek.cancel()
        player?.seekTo(max(0.0, positionSeconds).times(1000).toLong())
    }

    override fun setSpeed(value: Float) {
        player?.setPlaybackSpeed(value.coerceIn(.25f, 3f))
    }

    override fun release() {
        handler.removeCallbacks(ticker)
        initialSeek.cancel()
        playerView?.player = null
        playerView = null
        player?.release()
        player = null
    }
}

private fun redactPlaybackUrl(value: String): String =
    value.replace(Regex("(?i)([?&]access=)[^&\\s\\\"']+"), "$1<redacted>")

class MpvPlaybackEngine(
    private val context: Context,
    private val settings: MpvPlaybackSettings = MpvPlaybackSettings(),
) : PlaybackEngine {
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state
    private val handler = Handler(Looper.getMainLooper())
    private var view: MpvSurfaceView? = null
    private var pending: PendingPlayback? = null
    private var lastRequest: PendingPlayback? = null
    private var knownHdr = false
    private val localRecovery = LocalDecoderRecovery()

    override fun setKnownHdr(value: Boolean) {
        knownHdr = value
    }

    override fun recoverLocal(trackType: Int?): Boolean {
        val request = lastRequest?.takeIf { it.url.startsWith("file:") } ?: return false
        if (trackType == C.TRACK_TYPE_AUDIO || view == null) return false
        val hdr =
            knownHdr ||
                MPVLib.getPropertyString("video-params/gamma") in
                    setOf("pq", "hlg", "smpte2084", "arib-std-b67")
        if (!localRecovery.attempt(trackType, hdr)) return false
        val position = currentPositionSeconds()
        val ordinal = audioOrdinal
        MPVLib.setPropertyString("hwdec", "no")
        prepare(request.url, position, request.mimeType, request.playWhenReady)
        ordinal?.let(::selectAudioTrack)
        return true
    }

    private var audioOrdinal: Int? = null

    private fun applyAudioSelection() {
        if (!sourceReady || view == null) return
        val ordinal = audioOrdinal ?: return
        val count = MPVLib.getPropertyInt("track-list/count") ?: return
        val index =
            (0 until count)
                .filter { MPVLib.getPropertyString("track-list/$it/type") == "audio" }
                .getOrNull(ordinal) ?: return
        MPVLib.getPropertyInt("track-list/$index/id")?.let { MPVLib.setPropertyInt("aid", it) }
    }

    override fun selectAudioTrack(ordinal: Int) {
        require(ordinal >= 0)
        audioOrdinal = ordinal
        pending = pending?.copy(audioOrdinal = ordinal)
        applyAudioSelection()
    }

    private val initialSeek = InitialSeekController()
    private val endFileGate = MpvEndFileGate()
    private var released = false
    private var playWhenReady = false
    private var sourceReady = false
    private var sourceLoadPending = false
    private val eventObserver =
        object : MPVLib.EventObserver {
            override fun eventProperty(property: String) = Unit

            override fun eventProperty(property: String, value: Long) = Unit

            override fun eventProperty(property: String, value: Boolean) = Unit

            override fun eventProperty(property: String, value: String) = Unit

            override fun eventProperty(property: String, value: Double) = Unit

            override fun event(eventId: Int) {
                if (!released && sourceLoadPending && eventId in MPV_READY_EVENTS) {
                    sourceReady = true
                    sourceLoadPending = false
                    endFileGate.onSourceReady()
                    applyAudioSelection()
                    Log.d("ZenStreamPlayback", "MPV source became ready event=$eventId")
                    _state.value = _state.value.copy(ready = true, isBuffering = false)
                }
                if (!released && eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
                    // MPV also emits END_FILE when loadfile replaces the current source. That
                    // stop event must not be mistaken for natural completion and advance Next Up.
                    // With keep-open enabled, eof-reached distinguishes a real playback end from
                    // those replacement events even if an earlier readiness event cleared the
                    // one-shot suppression flag.
                    val eofReached =
                        runCatching { MPVLib.getPropertyBoolean("eof-reached") }.getOrNull() == true
                    if (endFileGate.shouldReportEndFile(eofReached)) {
                        _state.value = _state.value.copy(ended = true)
                    }
                }
            }
        }
    private val ticker =
        object : Runnable {
            override fun run() {
                if (released) return
                if (view == null) return
                val position =
                    runCatching { MPVLib.getPropertyDouble("time-pos") }.getOrNull() ?: 0.0
                val duration =
                    runCatching { MPVLib.getPropertyDouble("duration") }.getOrNull() ?: 0.0
                val paused = runCatching { MPVLib.getPropertyBoolean("pause") }.getOrNull() ?: true
                // keep-open=always can pause at EOF without emitting END_FILE. Poll the
                // EOF property too, but ignore the outgoing file while a source is loading.
                val eofReached =
                    runCatching { MPVLib.getPropertyBoolean("eof-reached") }.getOrNull()
                if (duration > 0.0) {
                    initialSeek.consume()?.let {
                        MPVLib.command(arrayOf("seek", it.toString(), "absolute+exact"))
                    }
                }
                _state.value =
                    _state.value.copy(
                        positionSeconds = position,
                        durationSeconds = duration,
                        bufferedSeconds =
                            position +
                                (runCatching {
                                        MPVLib.getPropertyDouble("demuxer-cache-duration")
                                    }
                                    .getOrNull() ?: 0.0),
                        isPlaying = !paused,
                        isBuffering =
                            runCatching {
                                    MPVLib.getPropertyBoolean("paused-for-cache") == true
                                }
                                .getOrDefault(false),
                        speed =
                            (runCatching { MPVLib.getPropertyDouble("speed") }.getOrNull() ?: 1.0)
                                .toFloat(),
                        ready = sourceReady,
                        ended = mpvPlaybackEnded(_state.value, sourceReady, eofReached),
                    )
                handler.postDelayed(this, 250L)
            }
        }

    override fun createView(context: Context): View {
        check(!released) { "Playback engine has been released" }
        if (view == null) {
            val configDir = context.filesDir.resolve("mpv-config").apply { mkdirs() }
            val cacheDir = context.cacheDir.resolve("mpv-cache").apply { mkdirs() }
            view =
                MpvSurfaceView(context, settings) { playWhenReady }
                    .also {
                        it.initialize(configDir.absolutePath, cacheDir.absolutePath)
                    }
            MPVLib.addObserver(eventObserver)
            handler.post(ticker)
        }
        return view!!.also {
            pending?.let { request ->
                prepare(
                    request.url,
                    request.startPositionSeconds,
                    request.mimeType,
                    request.playWhenReady,
                )
                request.audioOrdinal?.let(::selectAudioTrack)
            }
        }
    }

    override fun currentPositionSeconds(): Double {
        if (released || view == null) return _state.value.positionSeconds
        return runCatching { MPVLib.getPropertyDouble("time-pos") }
            .getOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 } ?: _state.value.positionSeconds
    }

    override fun prepare(
        url: String,
        startPositionSeconds: Double,
        mimeType: String?,
        playWhenReady: Boolean,
    ) {
        if (released) return
        localRecovery.source(url)
        lastRequest = PendingPlayback(url, startPositionSeconds, mimeType, playWhenReady)
        audioOrdinal = null
        this.playWhenReady = playWhenReady
        pending = PendingPlayback(url, startPositionSeconds, mimeType, playWhenReady)
        sourceReady = false
        sourceLoadPending = true
        initialSeek.schedule(startPositionSeconds)
        val current = view ?: return
        pending = null
        MPVLib.setPropertyString("hwdec", if (localRecovery.softwareVideo) "no" else "mediacodec")
        MPVLib.setPropertyString("aid", "auto")
        endFileGate.onSourceLoading()
        current.load(mpvPlaybackPath(url))
        if (current.hasSurface()) MPVLib.setPropertyBoolean("pause", !playWhenReady)
        _state.value = EngineState()
    }

    override fun play() {
        playWhenReady = true
        if (!released && view != null) MPVLib.setPropertyBoolean("pause", false)
    }

    override fun pause() {
        playWhenReady = false
        if (!released && view != null) MPVLib.setPropertyBoolean("pause", true)
    }

    override fun stop() {
        pending = null
        sourceReady = false
        sourceLoadPending = false
        if (!released && view != null) MPVLib.command(arrayOf("stop"))
        _state.value = EngineState()
    }

    override fun seekTo(positionSeconds: Double) {
        if (released) return
        initialSeek.cancel()
        if (view != null) {
            MPVLib.command(arrayOf("seek", max(0.0, positionSeconds).toString(), "absolute+exact"))
        }
    }

    override fun setSpeed(value: Float) {
        if (!released && view != null) {
            MPVLib.setPropertyDouble("speed", value.coerceIn(.25f, 3f).toDouble())
        }
    }

    override fun release() {
        if (released) return
        released = true
        sourceLoadPending = false
        handler.removeCallbacks(ticker)
        MPVLib.removeObserver(eventObserver)
        initialSeek.cancel()
        endFileGate.clear()
        view?.requestDestroy() ?: Unit
    }

    private class MpvSurfaceView(
        context: Context,
        private val settings: MpvPlaybackSettings,
        private val playWhenReady: () -> Boolean,
    ) : BaseMPVView(context, null) {
        private val lifecycle = MpvSurfaceLifecycle()
        private var destroyAfterSurfaceTeardown = false

        override fun initOptions() {
            mpvVideoRenderingOptions(settings).forEach { (name, value) ->
                MPVLib.setOptionString(name, value)
            }
            MPVLib.setOptionString("vo", settings.videoOutput.storageValue)
            MPVLib.setOptionString("ao", "aaudio")
            MPVLib.setOptionString("gpu-context", "android")
            MPVLib.setOptionString("opengl-es", "yes")
            MPVLib.setOptionString("hwdec", "mediacodec")
            MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
            MPVLib.setOptionString("keep-open", "always")
            MPVLib.setOptionString("audio-client-name", "ZenStream")
            mpvCaptionOptions.forEach { (name, value) -> MPVLib.setOptionString(name, value) }
        }

        override fun postInitOptions() {
            setVo(settings.videoOutput.storageValue)
        }

        override fun observeProperties() = Unit

        fun hasSurface(): Boolean = lifecycle.hasSurface()

        fun load(url: String) {
            if (lifecycle.hasSurface()) {
                MPVLib.command(arrayOf("loadfile", url, "replace"))
            } else {
                playFile(url)
            }
        }

        override fun surfaceChanged(
            holder: android.view.SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            if (lifecycle.canUseSurface()) super.surfaceChanged(holder, format, width, height)
        }

        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
            if (!lifecycle.canUseSurface()) return
            super.surfaceCreated(holder)
            lifecycle.markSurfaceCreated()
            MPVLib.setPropertyBoolean("pause", !playWhenReady())
        }

        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
            if (!lifecycle.canUseSurface()) return
            destroyAfterSurfaceTeardown = lifecycle.markSurfaceDestroyed()
            super.surfaceDestroyed(holder)
            if (destroyAfterSurfaceTeardown) post { destroyNow() }
        }

        fun requestDestroy() {
            if (lifecycle.requestDestroy()) destroyNow()
        }

        private fun destroyNow() {
            if (lifecycle.markDestroyed()) super.destroy()
        }
    }
}

private val MPV_READY_EVENTS =
    setOf(
        MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED,
        MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART,
    )

fun createPlaybackEngine(
    engine: PlayerEngine,
    context: Context,
    mpvPlaybackSettings: MpvPlaybackSettings = MpvPlaybackSettings(),
): PlaybackEngine =
    when (engine) {
        PlayerEngine.MEDIA3 -> Media3PlaybackEngine()
        PlayerEngine.MPV -> MpvPlaybackEngine(context, mpvPlaybackSettings)
    }

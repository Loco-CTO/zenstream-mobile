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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.zenstream.zenstreammobile.model.PlayerEngine
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVLib
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal val mpvCaptionOptions =
    mapOf(
        "sub-auto" to "no",
        "sid" to "no",
        "secondary-sid" to "no",
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
)

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

internal class MpvEndFileGate {
    private var sourceLoaded = false
    private var ignoreReplacementEnd = false

    fun onSourceLoading() {
        if (sourceLoaded) ignoreReplacementEnd = true
        sourceLoaded = true
    }

    fun shouldReportEndFile(): Boolean {
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

    fun seekTo(positionSeconds: Double)

    fun setSpeed(value: Float)

    fun release()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3PlaybackEngine : PlaybackEngine {
    private val tag = "ZenStreamPlayback"
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var pending: PendingPlayback? = null
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
        if (player == null) {
            player =
                ExoPlayer.Builder(context.applicationContext)
                    .setAudioAttributes(
                        androidx.media3.common.AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        true,
                    )
                    .setHandleAudioBecomingNoisy(true)
                    .build().also { exo ->
                    exo.trackSelectionParameters =
                        exo.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                    exo.addListener(
                        object : Player.Listener {
                            override fun onPlayerError(error: PlaybackException) {
                                Log.e(
                                    tag,
                                    "Media3 playback error code=${error.errorCodeName} message=${error.message}",
                                )
                                _state.value =
                                    _state.value.copy(error = error.message ?: "Playback failed")
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                _state.value =
                                    _state.value.copy(
                                        ready = playbackState == Player.STATE_READY,
                                        isBuffering = playbackState == Player.STATE_BUFFERING,
                                        ended = playbackState == Player.STATE_ENDED,
                                    )
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
        return PlayerView(context)
            .apply {
                useController = false
                subtitleView?.visibility = View.GONE
                player = this@Media3PlaybackEngine.player
            }
            .also {
                pending?.let { request ->
                    prepare(
                        request.url,
                        request.startPositionSeconds,
                        request.mimeType,
                        request.playWhenReady,
                    )
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
        if (current == null) {
            pending = PendingPlayback(url, startPositionSeconds, mimeType, playWhenReady)
            return
        }
        pending = null
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
        _state.value = EngineState()
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
        player?.release()
        player = null
    }
}

private fun redactPlaybackUrl(value: String): String =
    value.replace(Regex("(?i)([?&]access=)[^&\\s\\\"']+"), "$1<redacted>")

class MpvPlaybackEngine(private val context: Context) : PlaybackEngine {
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state
    private val handler = Handler(Looper.getMainLooper())
    private var view: MpvSurfaceView? = null
    private var pending: PendingPlayback? = null
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
                    Log.d("ZenStreamPlayback", "MPV source became ready event=$eventId")
                    _state.value = _state.value.copy(ready = true, isBuffering = false)
                }
                if (!released && playWhenReady && eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
                    if (endFileGate.shouldReportEndFile()) {
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
                MpvSurfaceView(context) { playWhenReady }
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
        this.playWhenReady = playWhenReady
        pending = PendingPlayback(url, startPositionSeconds, mimeType, playWhenReady)
        sourceReady = false
        sourceLoadPending = true
        initialSeek.schedule(startPositionSeconds)
        val current = view ?: return
        pending = null
        endFileGate.onSourceLoading()
        current.load(url)
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
        private val playWhenReady: () -> Boolean,
    ) : BaseMPVView(context, null) {
        private val lifecycle = MpvSurfaceLifecycle()
        private var destroyAfterSurfaceTeardown = false

        override fun initOptions() {
            MPVLib.setOptionString("profile", "fast")
            MPVLib.setOptionString("vo", "gpu-next")
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
            setVo("gpu-next")
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
        MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG,
        MPVLib.MpvEvent.MPV_EVENT_AUDIO_RECONFIG,
    )

fun createPlaybackEngine(engine: PlayerEngine, context: Context): PlaybackEngine =
    when (engine) {
        PlayerEngine.MEDIA3 -> Media3PlaybackEngine()
        PlayerEngine.MPV -> MpvPlaybackEngine(context)
    }

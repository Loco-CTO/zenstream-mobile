package com.zenstream.zenstreammobile.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.zenstream.zenstreammobile.model.PlayerEngine
import `is`.xyz.mpv.BaseMPVView
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max

internal val mpvCaptionOptions = mapOf(
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

interface PlaybackEngine {
    val state: StateFlow<EngineState>
    fun createView(context: Context): View
    fun currentPositionSeconds(): Double = state.value.positionSeconds

    /** Loads the source and starts playback once it is ready. */
    fun prepare(url: String, startPositionSeconds: Double, mimeType: String? = null)
    fun play()
    fun pause()
    fun seekTo(positionSeconds: Double)
    fun setSpeed(value: Float)
    fun release()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3PlaybackEngine : PlaybackEngine {
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var pending: Pair<String, Double>? = null
    private val initialSeek = InitialSeekController()
    private val ticker = object : Runnable {
        override fun run() {
            val current = player
            if (current != null) {
                val duration = current.duration.takeIf { it > 0 } ?: 0L
                _state.value = _state.value.copy(
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
            player = ExoPlayer.Builder(context.applicationContext).build().also { exo ->
                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        _state.value = _state.value.copy(error = error.message ?: "Playback failed")
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _state.value = _state.value.copy(
                            ready = playbackState == Player.STATE_READY,
                            isBuffering = playbackState == Player.STATE_BUFFERING,
                        )
                        if (playbackState == Player.STATE_READY) applyInitialSeek()
                    }
                })
            }
            handler.post(ticker)
        }
        return PlayerView(context).apply {
            useController = false
            subtitleView?.visibility = View.GONE
            player = this@Media3PlaybackEngine.player
        }.also { pending?.let { (url, start) -> prepare(url, start) } }
    }

    override fun currentPositionSeconds(): Double =
        player?.currentPosition?.coerceAtLeast(0L)?.div(1000.0)
            ?: _state.value.positionSeconds

    override fun prepare(url: String, startPositionSeconds: Double, mimeType: String?) {
        val current = player
        if (current == null) {
            pending = url to startPositionSeconds
            return
        }
        pending = null
        initialSeek.schedule(startPositionSeconds)
        current.playWhenReady = true
        val mediaItem = MediaItem.Builder()
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

class MpvPlaybackEngine(private val context: Context) : PlaybackEngine {
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state
    private val handler = Handler(Looper.getMainLooper())
    private var view: MpvSurfaceView? = null
    private var pending: Pair<String, Double>? = null
    private val initialSeek = InitialSeekController()
    private var released = false
    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            if (view == null) return
            val position = runCatching { MPVLib.getPropertyDouble("time-pos") }.getOrNull() ?: 0.0
            val duration = runCatching { MPVLib.getPropertyDouble("duration") }.getOrNull() ?: 0.0
            val paused = runCatching { MPVLib.getPropertyBoolean("pause") }.getOrNull() ?: true
            if (duration > 0.0) {
                initialSeek.consume()?.let {
                    MPVLib.command(arrayOf("seek", it.toString(), "absolute+exact"))
                }
            }
            _state.value = _state.value.copy(
                positionSeconds = position,
                durationSeconds = duration,
                bufferedSeconds = position + (runCatching {
                    MPVLib.getPropertyDouble("demuxer-cache-duration")
                }.getOrNull() ?: 0.0),
                isPlaying = !paused,
                isBuffering = runCatching {
                    MPVLib.getPropertyBoolean("paused-for-cache") == true
                }.getOrDefault(false),
                speed = (runCatching { MPVLib.getPropertyDouble("speed") }.getOrNull()
                    ?: 1.0).toFloat(),
                ready = duration > 0,
            )
            handler.postDelayed(this, 250L)
        }
    }

    override fun createView(context: Context): View {
        check(!released) { "Playback engine has been released" }
        if (view == null) {
            val configDir = context.filesDir.resolve("mpv-config").apply { mkdirs() }
            val cacheDir = context.cacheDir.resolve("mpv-cache").apply { mkdirs() }
            view = MpvSurfaceView(context).also {
                it.initialize(configDir.absolutePath, cacheDir.absolutePath)
            }
            handler.post(ticker)
        }
        return view!!.also { pending?.let { (url, start) -> prepare(url, start) } }
    }

    override fun currentPositionSeconds(): Double {
        if (released || view == null) return _state.value.positionSeconds
        return runCatching { MPVLib.getPropertyDouble("time-pos") }
            .getOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: _state.value.positionSeconds
    }

    override fun prepare(url: String, startPositionSeconds: Double, mimeType: String?) {
        if (released) return
        pending = url to startPositionSeconds
        initialSeek.schedule(startPositionSeconds)
        val current = view ?: return
        pending = null
        current.load(url)
        if (current.hasSurface()) MPVLib.setPropertyBoolean("pause", false)
        _state.value = EngineState()
    }

    override fun play() {
        if (!released && view != null) MPVLib.setPropertyBoolean("pause", false)
    }

    override fun pause() {
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
        handler.removeCallbacks(ticker)
        initialSeek.cancel()
        view?.requestDestroy() ?: Unit
    }

    private class MpvSurfaceView(context: Context) : BaseMPVView(context, null) {
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
            height: Int
        ) {
            if (lifecycle.canUseSurface()) super.surfaceChanged(holder, format, width, height)
        }

        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
            if (!lifecycle.canUseSurface()) return
            super.surfaceCreated(holder)
            lifecycle.markSurfaceCreated()
            MPVLib.setPropertyBoolean("pause", false)
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

fun createPlaybackEngine(engine: PlayerEngine, context: Context): PlaybackEngine = when (engine) {
    PlayerEngine.MEDIA3 -> Media3PlaybackEngine()
    PlayerEngine.MPV -> MpvPlaybackEngine(context)
}

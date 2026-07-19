package com.zenstream.zenstreammobile.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
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
    fun prepare(url: String, startPositionSeconds: Double)
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

    override fun prepare(url: String, startPositionSeconds: Double) {
        val current = player
        if (current == null) {
            pending = url to startPositionSeconds
            return
        }
        pending = null
        initialSeek.schedule(startPositionSeconds)
        current.playWhenReady = true
        current.setMediaItem(MediaItem.fromUri(url))
        current.prepare()
        _state.value = EngineState()
    }

    private fun applyInitialSeek() {
        val positionSeconds = initialSeek.consume() ?: return
        player?.seekTo((positionSeconds * 1000).toLong())
    }

    override fun play() { player?.play() }
    override fun pause() { player?.pause() }
    override fun seekTo(positionSeconds: Double) {
        initialSeek.cancel()
        player?.seekTo(max(0.0, positionSeconds).times(1000).toLong())
    }
    override fun setSpeed(value: Float) { player?.setPlaybackSpeed(value.coerceIn(.25f, 3f)) }
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
    private var pendingUrl: String? = null
    private var released = false
    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            val position = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
            val paused = MPVLib.getPropertyBoolean("pause") ?: true
            val path = runCatching { MPVLib.getPropertyString("path") }.getOrNull()
            if (duration > 0.0 && (pendingUrl == null || path == pendingUrl)) {
                initialSeek.consume()?.let { MPVLib.command("seek", it.toString(), "absolute+exact") }
                if (path == pendingUrl) pendingUrl = null
            }
            _state.value = _state.value.copy(
                positionSeconds = position,
                durationSeconds = duration,
                bufferedSeconds = position + (MPVLib.getPropertyDouble("demuxer-cache-duration") ?: 0.0),
                isPlaying = !paused,
                isBuffering = MPVLib.getPropertyBoolean("paused-for-cache") == true,
                speed = (MPVLib.getPropertyDouble("speed") ?: 1.0).toFloat(),
                ready = duration > 0,
            )
            handler.postDelayed(this, 250L)
        }
    }

    override fun createView(context: Context): View {
        check(!released) { "Playback engine has been released" }
        if (view == null) {
            context.filesDir.resolve("mpv-config").mkdirs()
            context.cacheDir.resolve("mpv-cache").mkdirs()
            view = MpvSurfaceView(context).also {
                it.initialize(
                    context.filesDir.resolve("mpv-config").absolutePath,
                    context.cacheDir.resolve("mpv-cache").absolutePath,
                )
            }
            handler.post(ticker)
        }
        return view!!.also { pending?.let { (url, start) -> prepare(url, start) } }
    }

    override fun currentPositionSeconds(): Double = runCatching {
        if (released) return@runCatching null
        MPVLib.getPropertyDouble("time-pos")
    }.getOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: _state.value.positionSeconds

    override fun prepare(url: String, startPositionSeconds: Double) {
        if (released) return
        pending = url to startPositionSeconds
        initialSeek.schedule(startPositionSeconds)
        pendingUrl = url
        val current = view ?: return
        current.load(url)
        MPVLib.setPropertyBoolean("pause", false)
        _state.value = EngineState()
    }

    override fun play() {
        if (!released) MPVLib.setPropertyBoolean("pause", false)
    }
    override fun pause() {
        if (!released) MPVLib.setPropertyBoolean("pause", true)
    }
    override fun seekTo(positionSeconds: Double) {
        if (released) return
        initialSeek.cancel()
        MPVLib.command("seek", max(0.0, positionSeconds).toString(), "absolute+exact")
    }
    override fun setSpeed(value: Float) {
        if (!released) MPVLib.setPropertyDouble("speed", value.coerceIn(.25f, 3f).toDouble())
    }
    override fun release() {
        if (released) return
        released = true
        handler.removeCallbacks(ticker)
        initialSeek.cancel()
        pendingUrl = null
        view?.requestDestroy()
        view = null
    }

    private class MpvSurfaceView(context: Context) : BaseMPVView(context, EmptyAttributeSet) {
        private val lifecycle = MpvSurfaceLifecycle()

        override fun initOptions() {
            MPVLib.setOptionString("vo", "gpu")
            MPVLib.setOptionString("hwdec", "auto-safe")
            mpvCaptionOptions.forEach { (name, value) -> MPVLib.setOptionString(name, value) }
            MPVLib.setOptionString("audio-client-name", "ZenStream")
        }

        override fun postInitOptions() = Unit
        override fun observeProperties() = Unit

        fun load(url: String) {
            if (lifecycle.hasSurface()) MPVLib.command("loadfile", url, "replace") else playFile(url)
        }

        fun requestDestroy() {
            if (lifecycle.requestDestroy()) destroyNow()
        }

        private fun destroyNow() {
            if (!lifecycle.markDestroyed()) return
            super.destroy()
        }

        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
            if (!lifecycle.canUseSurface()) return
            super.surfaceCreated(holder)
            lifecycle.markSurfaceCreated()
        }

        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
            if (!lifecycle.canUseSurface()) return
            val destroyAfterSurfaceTeardown = lifecycle.markSurfaceDestroyed()
            super.surfaceDestroyed(holder)
            if (destroyAfterSurfaceTeardown) {
                // Let the surface callback finish before destroying libmpv. The
                // native renderer can still be using the surface during the callback.
                post(::destroyNow)
            }
        }
    }

    private object EmptyAttributeSet : android.util.AttributeSet {
        override fun getAttributeCount() = 0
        override fun getAttributeName(index: Int): String? = null
        override fun getAttributeValue(index: Int): String? = null
        override fun getAttributeValue(namespace: String?, name: String): String? = null
        override fun getAttributeResourceValue(namespace: String?, name: String, defaultValue: Int) = defaultValue
        override fun getAttributeResourceValue(index: Int, defaultValue: Int) = defaultValue
        override fun getAttributeIntValue(namespace: String?, name: String, defaultValue: Int) = defaultValue
        override fun getAttributeIntValue(index: Int, defaultValue: Int) = defaultValue
        override fun getAttributeUnsignedIntValue(namespace: String?, name: String, defaultValue: Int) = defaultValue
        override fun getAttributeUnsignedIntValue(index: Int, defaultValue: Int) = defaultValue
        override fun getAttributeBooleanValue(namespace: String?, name: String, defaultValue: Boolean) = defaultValue
        override fun getAttributeBooleanValue(index: Int, defaultValue: Boolean) = defaultValue
        override fun getAttributeFloatValue(namespace: String?, name: String, defaultValue: Float) = defaultValue
        override fun getAttributeFloatValue(index: Int, defaultValue: Float) = defaultValue
        override fun getAttributeListValue(namespace: String?, attribute: String, options: Array<out String>, defaultValue: Int) = defaultValue
        override fun getAttributeListValue(index: Int, options: Array<out String>, defaultValue: Int) = defaultValue
        override fun getAttributeNameResource(index: Int) = 0
        override fun getPositionDescription() = ""
        override fun getIdAttribute(): String? = null
        override fun getClassAttribute(): String? = null
        override fun getIdAttributeResourceValue(defaultValue: Int) = defaultValue
        override fun getStyleAttribute() = 0
    }
}

fun createPlaybackEngine(engine: PlayerEngine, context: Context): PlaybackEngine = when (engine) {
    PlayerEngine.MEDIA3 -> Media3PlaybackEngine()
    PlayerEngine.MPV -> MpvPlaybackEngine(context)
}

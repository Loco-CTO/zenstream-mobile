package com.zenstream.zenstreammobile.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
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

data class EngineState(
    val positionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val bufferedSeconds: Double = 0.0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val speed: Float = 1f,
    val ready: Boolean = false,
    val error: String? = null,
)

interface PlaybackEngine {
    val state: StateFlow<EngineState>
    fun createView(context: Context): View
    fun prepare(url: String, startPositionSeconds: Double)
    fun play()
    fun pause()
    fun seekTo(positionSeconds: Double)
    fun setVolume(value: Float)
    fun setMuted(value: Boolean)
    fun setSpeed(value: Float)
    fun release()
}

class Media3PlaybackEngine : PlaybackEngine {
    private val _state = MutableStateFlow(EngineState())
    override val state: StateFlow<EngineState> = _state
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var pending: Pair<String, Double>? = null
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
                    volume = current.volume,
                    muted = current.volume == 0f,
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
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        _state.value = _state.value.copy(error = error.message ?: "Playback failed")
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        _state.value = _state.value.copy(
                            ready = playbackState == Player.STATE_READY,
                            isBuffering = playbackState == Player.STATE_BUFFERING,
                        )
                    }
                })
            }
            handler.post(ticker)
        }
        return PlayerView(context).apply {
            useController = false
            player = this@Media3PlaybackEngine.player
        }.also { pending?.let { (url, start) -> prepare(url, start) } }
    }

    override fun prepare(url: String, startPositionSeconds: Double) {
        val current = player
        if (current == null) {
            pending = url to startPositionSeconds
            return
        }
        pending = null
        current.setMediaItem(MediaItem.fromUri(url))
        current.prepare()
        if (startPositionSeconds > 0) current.seekTo((startPositionSeconds * 1000).toLong())
        _state.value = _state.value.copy(error = null)
    }

    override fun play() { player?.play() }
    override fun pause() { player?.pause() }
    override fun seekTo(positionSeconds: Double) { player?.seekTo(max(0.0, positionSeconds).times(1000).toLong()) }
    override fun setVolume(value: Float) { player?.volume = value.coerceIn(0f, 1f) }
    override fun setMuted(value: Boolean) { if (value) player?.volume = 0f else if (player?.volume == 0f) player?.volume = 1f }
    override fun setSpeed(value: Float) { player?.setPlaybackSpeed(value.coerceIn(.25f, 3f)) }
    override fun release() {
        handler.removeCallbacks(ticker)
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
    private val ticker = object : Runnable {
        override fun run() {
            val position = MPVLib.getPropertyDouble("time-pos") ?: 0.0
            val duration = MPVLib.getPropertyDouble("duration") ?: 0.0
            val paused = MPVLib.getPropertyBoolean("pause") ?: true
            val volume = ((MPVLib.getPropertyDouble("volume") ?: 100.0) / 100.0).toFloat().coerceIn(0f, 1f)
            _state.value = _state.value.copy(
                positionSeconds = position,
                durationSeconds = duration,
                bufferedSeconds = position + (MPVLib.getPropertyDouble("demuxer-cache-duration") ?: 0.0),
                isPlaying = !paused,
                isBuffering = MPVLib.getPropertyBoolean("paused-for-cache") == true,
                volume = volume,
                muted = volume == 0f,
                speed = (MPVLib.getPropertyDouble("speed") ?: 1.0).toFloat(),
                ready = duration > 0,
            )
            handler.postDelayed(this, 250L)
        }
    }

    override fun createView(context: Context): View {
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
        return view!!
    }

    override fun prepare(url: String, startPositionSeconds: Double) {
        pending = url to startPositionSeconds
        val current = view ?: return
        current.load(url)
        if (startPositionSeconds > 0) MPVLib.setPropertyDouble("start", startPositionSeconds)
        _state.value = _state.value.copy(error = null)
    }

    override fun play() { MPVLib.setPropertyBoolean("pause", false) }
    override fun pause() { MPVLib.setPropertyBoolean("pause", true) }
    override fun seekTo(positionSeconds: Double) { MPVLib.command("seek", max(0.0, positionSeconds).toString(), "absolute+exact") }
    override fun setVolume(value: Float) { MPVLib.setPropertyDouble("volume", value.coerceIn(0f, 1f) * 100.0) }
    override fun setMuted(value: Boolean) { MPVLib.setPropertyBoolean("mute", value) }
    override fun setSpeed(value: Float) { MPVLib.setPropertyDouble("speed", value.coerceIn(.25f, 3f).toDouble()) }
    override fun release() {
        handler.removeCallbacks(ticker)
        view?.destroy()
        view = null
    }

    private class MpvSurfaceView(context: Context) : BaseMPVView(context, EmptyAttributeSet) {
        private var surfaceReady = false

        override fun initOptions() {
            MPVLib.setOptionString("vo", "gpu")
            MPVLib.setOptionString("hwdec", "auto-safe")
            MPVLib.setOptionString("sub-auto", "no")
            MPVLib.setOptionString("audio-client-name", "ZenStream")
        }

        override fun postInitOptions() = Unit
        override fun observeProperties() = Unit

        fun load(url: String) {
            if (surfaceReady) MPVLib.command("loadfile", url, "replace") else playFile(url)
        }

        override fun surfaceCreated(holder: android.view.SurfaceHolder) {
            super.surfaceCreated(holder)
            surfaceReady = true
        }

        override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
            surfaceReady = false
            super.surfaceDestroyed(holder)
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

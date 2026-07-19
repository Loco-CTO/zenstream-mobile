package com.zenstream.zenstreammobile.ui

import android.os.SystemClock
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zenstream.zenstreammobile.data.JellyfinRepository
import com.zenstream.zenstreammobile.data.activeSubtitleCues
import com.zenstream.zenstreammobile.data.isCurrentSubtitleRequest
import com.zenstream.zenstreammobile.data.playbackUrl
import com.zenstream.zenstreammobile.data.playbackLocalPositionSeconds
import com.zenstream.zenstreammobile.data.playbackStreamStartPositionSeconds
import com.zenstream.zenstreammobile.data.parseWebVttCues
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.model.PlaybackData
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.PlaybackSegment
import com.zenstream.zenstreammobile.model.SubtitleCue
import com.zenstream.zenstreammobile.model.SubtitleStyle
import com.zenstream.zenstreammobile.ui.player.EngineState
import com.zenstream.zenstreammobile.ui.player.PlaybackEngine
import com.zenstream.zenstreammobile.ui.player.createPlaybackEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.view.View

data class PlaybackUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val itemName: String = "",
    val itemId: String = "",
    val playback: PlaybackData? = null,
    val engineType: PlayerEngine = PlayerEngine.MEDIA3,
    val engine: EngineState = EngineState(),
    val selectedAudio: Int? = null,
    val selectedSubtitle: Int? = null,
    val selectedQuality: Int = 0,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val subtitleCues: List<SubtitleCue> = emptyList(),
    val subtitleOffset: Double = 0.0,
    val mediaOriginSeconds: Double = 0.0,
    val segments: List<PlaybackSegment> = emptyList(),
) {
    val activeCues: List<SubtitleCue>
        get() = activeCuesAt(engine.positionSeconds)

    fun activeCuesAt(positionSeconds: Double): List<SubtitleCue> =
        activeSubtitleCues(
            cues = subtitleCues,
            positionSeconds = positionSeconds,
            offsetSeconds = subtitleOffset,
            timelineOriginSeconds = mediaOriginSeconds,
        )

    fun activeSegmentAt(positionSeconds: Double): PlaybackSegment? =
        segments.firstOrNull { positionSeconds >= it.startSeconds && positionSeconds < it.endSeconds }
}

private data class PlaybackProgressSnapshot(
    val positionSeconds: Double,
    val paused: Boolean,
    val playSessionId: String?,
    val playbackGeneration: Long,
)

class PlaybackViewModel(
    private val repository: JellyfinRepository,
    private val session: AuthSession,
    private val itemId: String,
    private val appContext: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlaybackUiState(itemId = itemId))
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()
    private var playbackEngine: PlaybackEngine? = null
    private var engineJob: Job? = null
    private var progressJob: Job? = null
    private var progressFlushJob: Job? = null
    private var playbackLoadJob: Job? = null
    private var subtitleJob: Job? = null
    private var playbackGeneration = 0L
    private var subtitleGeneration = 0L
    private var mediaOriginSeconds = 0.0
    private var recovered = false

    init {
        viewModelScope.launch {
            val engineType = repository.playerEngine.first()
            val subtitleStyle = repository.loadSubtitleStyle()
            _uiState.value = _uiState.value.copy(engineType = engineType, subtitleStyle = subtitleStyle)
            createEngine(engineType)
            loadPlayback()
        }
    }

    private fun createEngine(type: PlayerEngine) {
        engineJob?.cancel()
        playbackEngine?.release()
        playbackEngine = createPlaybackEngine(type, appContext)
        engineJob = viewModelScope.launch {
            playbackEngine?.state?.collectLatest { state ->
                _uiState.value = _uiState.value.copy(engine = state, error = state.error)
                if (state.error != null && !recovered && _uiState.value.playback?.source?.transcodingUrl == null) {
                    recovered = true
                    loadPlayback(PlaybackOptions(forceTranscoding = true, maxStreamingBitrate = 1_000_000))
                }
            }
        }
    }

    fun createView(context: Context): View? = runCatching {
        playbackEngine?.createView(context)
    }.getOrElse {
        fallbackToMedia3()
        playbackEngine?.createView(context)
    }

    private fun fallbackToMedia3() {
        if (_uiState.value.engineType == PlayerEngine.MEDIA3) return
        val currentPosition = currentPlayerPositionSeconds()
        createEngine(PlayerEngine.MEDIA3)
        _uiState.value = _uiState.value.copy(engineType = PlayerEngine.MEDIA3, error = null)
        viewModelScope.launch { repository.savePlayerEngine(PlayerEngine.MEDIA3) }
        val playback = _uiState.value.playback
        if (playback != null) {
            playbackEngine?.prepare(
                playbackUrl(session, itemId, playback.source, _uiState.value.selectedQuality, ticks(mediaOriginSeconds)),
                currentPosition,
            )
        }
    }

    private fun loadPlayback(options: PlaybackOptions = PlaybackOptions()) {
        val loadGeneration = ++playbackGeneration
        playbackLoadJob?.cancel()
        playbackLoadJob = viewModelScope.launch {
            val hasCurrentPlayback = _uiState.value.playback != null
            val currentPosition = currentPlayerPositionSeconds()
            val requestedStartSeconds = if (hasCurrentPlayback) {
                mediaOriginSeconds + currentPosition
            } else {
                options.startTimeTicks / 10_000_000.0
            }.coerceAtLeast(0.0)
            val requestOptions = options.copy(
                startTimeTicks = if (hasCurrentPlayback) ticks(requestedStartSeconds) else options.startTimeTicks,
            )
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            val data = runCatching {
                repository.playback(session, itemId, requestOptions)
            }.getOrElse {
                if (loadGeneration == playbackGeneration) {
                    _uiState.value = _uiState.value.copy(loading = false, error = it.message ?: "Playback failed")
                }
                return@launch
            }
            if (loadGeneration != playbackGeneration) return@launch

            val selectedAudio = requestOptions.audioStreamIndex
                ?: data.audio.firstOrNull { it.isDefault }?.index
                ?: data.audio.firstOrNull()?.index
            val selectedSubtitle = _uiState.value.selectedSubtitle
                ?: data.subtitles.firstOrNull { it.isDefault }?.index
            val requestedOrResumeStartSeconds = if (hasCurrentPlayback || requestOptions.startTimeTicks > 0) {
                requestedStartSeconds
            } else {
                data.item.playbackPositionTicks.orZero() / 10_000_000.0
            }
            val sourceOriginSeconds = playbackStreamStartPositionSeconds(
                session,
                data.source,
                requestedOrResumeStartSeconds,
            )
            val localStartSeconds = playbackLocalPositionSeconds(requestedOrResumeStartSeconds, sourceOriginSeconds)
            val bitrate = requestOptions.maxStreamingBitrate ?: 0
            mediaOriginSeconds = sourceOriginSeconds
            val subtitleLoadGeneration = ++subtitleGeneration
            subtitleJob?.cancel()
            _uiState.value = _uiState.value.copy(
                loading = false,
                itemName = data.item.name,
                playback = data,
                selectedAudio = selectedAudio,
                selectedSubtitle = selectedSubtitle,
                selectedQuality = bitrate,
                mediaOriginSeconds = sourceOriginSeconds,
                subtitleCues = emptyList(),
                segments = data.segments.mapNotNull { segment ->
                    val start = segment.startSeconds - sourceOriginSeconds
                    val end = segment.endSeconds - sourceOriginSeconds
                    if (end <= 0.0) null else segment.copy(
                        startSeconds = start.coerceAtLeast(0.0),
                        endSeconds = end,
                    )
                },
                error = null,
            )
            playbackEngine?.prepare(
                playbackUrl(session, itemId, data.source, bitrate, requestOptions.startTimeTicks),
                localStartSeconds,
            )
            loadSubtitle(loadGeneration, subtitleLoadGeneration)
            startProgressReporting()
        }
    }

    private fun Long?.orZero() = this ?: 0L

    private fun ticks(seconds: Double): Long =
        (seconds.coerceAtLeast(0.0) * 10_000_000.0).toLong()

    private fun currentPlayerPositionSeconds(): Double =
        playbackEngine?.currentPositionSeconds()?.takeIf { it.isFinite() && it >= 0.0 }
            ?: _uiState.value.engine.positionSeconds.coerceAtLeast(0.0)

    private fun startProgressReporting() {
        if (progressJob != null) return
        progressJob = viewModelScope.launch {
            while (true) {
                delay(10_000)
                reportProgress()
            }
        }
    }

    private suspend fun reportProgress() {
        reportProgress(playbackProgressSnapshot())
    }

    private suspend fun reportProgress(snapshot: PlaybackProgressSnapshot?) {
        if (snapshot == null) return
        if (snapshot.playbackGeneration != playbackGeneration) return
        runCatching {
            repository.reportPlayback(
                session,
                itemId,
                snapshot.positionSeconds,
                snapshot.paused,
                snapshot.playSessionId,
            )
        }
    }

    private fun playbackProgressSnapshot(): PlaybackProgressSnapshot? {
        val state = _uiState.value.engine
        val position = mediaOriginSeconds + currentPlayerPositionSeconds()
        return position.takeIf { it > 0 }?.let {
            PlaybackProgressSnapshot(
                positionSeconds = it,
                paused = !state.isPlaying,
                playSessionId = _uiState.value.playback?.playSessionId,
                playbackGeneration = playbackGeneration,
            )
        }
    }

    fun togglePlay() {
        if (_uiState.value.engine.isPlaying) playbackEngine?.pause() else playbackEngine?.play()
    }

    fun seekBy(deltaSeconds: Double) {
        val state = _uiState.value.engine
        playbackEngine?.seekTo((currentPlayerPositionSeconds() + deltaSeconds).coerceIn(0.0, state.durationSeconds.takeIf { it > 0 } ?: Double.MAX_VALUE))
    }

    fun seekTo(positionSeconds: Double) = playbackEngine?.seekTo(positionSeconds)
    fun skipSegment(segment: PlaybackSegment) = seekTo(segment.endSeconds)
    fun subtitlePositionSeconds(): Double = playbackEngine?.currentPositionSeconds()
        ?: _uiState.value.engine.positionSeconds
    fun setSpeed(value: Float) = playbackEngine?.setSpeed(value)

    fun chooseQuality(value: Int) {
        loadPlayback(PlaybackOptions(maxStreamingBitrate = value.takeIf { it > 0 }))
    }

    fun chooseAudio(stream: MediaStream) {
        _uiState.value = _uiState.value.copy(selectedAudio = stream.index)
        loadPlayback(PlaybackOptions(mediaSourceId = _uiState.value.playback?.source?.id, audioStreamIndex = stream.index))
    }

    fun chooseSubtitle(streamIndex: Int?) {
        subtitleJob?.cancel()
        val requestGeneration = ++subtitleGeneration
        _uiState.value = _uiState.value.copy(selectedSubtitle = streamIndex, subtitleCues = emptyList())
        if (streamIndex != null) loadSubtitle(playbackGeneration, requestGeneration)
    }

    private fun loadSubtitle(loadGeneration: Long, requestGeneration: Long) {
        val selected = _uiState.value.selectedSubtitle ?: return
        val playback = _uiState.value.playback ?: return
        val sourceId = playback.source.id
        subtitleJob?.cancel()
        subtitleJob = viewModelScope.launch {
            val body = runCatching {
                repository.subtitleWebVtt(session, itemId, sourceId, selected)
            }.getOrNull() ?: return@launch
            if (
                loadGeneration != playbackGeneration ||
                !isCurrentSubtitleRequest(
                    requestGeneration = requestGeneration,
                    currentGeneration = subtitleGeneration,
                    requestedTrack = selected,
                    currentTrack = _uiState.value.selectedSubtitle,
                    requestedSourceId = sourceId,
                    currentSourceId = _uiState.value.playback?.source?.id,
                )
            ) return@launch
            _uiState.value = _uiState.value.copy(subtitleCues = parseWebVttCues(body))
        }
    }

    fun setSubtitleOffset(value: Double) { _uiState.value = _uiState.value.copy(subtitleOffset = value) }

    fun updateSubtitleStyle(change: SubtitleStyle.() -> SubtitleStyle) {
        val next = change(_uiState.value.subtitleStyle)
        _uiState.value = _uiState.value.copy(subtitleStyle = next)
        viewModelScope.launch { repository.saveSubtitleStyle(next) }
    }

    fun flushProgress() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressFlushAt < PROGRESS_FLUSH_DEBOUNCE_MILLIS) return
        lastProgressFlushAt = now
        val snapshot = playbackProgressSnapshot()
        progressFlushJob?.cancel()
        if (snapshot != null) {
            progressFlushJob = viewModelScope.launch { reportProgress(snapshot) }
        }
    }

    fun onPause() = flushProgress()

    override fun onCleared() {
        progressJob?.cancel()
        progressFlushJob?.cancel()
        playbackLoadJob?.cancel()
        subtitleJob?.cancel()
        engineJob?.cancel()
        playbackEngine?.release()
        playbackEngine = null
        super.onCleared()
    }

    private var lastProgressFlushAt = 0L

    private companion object {
        const val PROGRESS_FLUSH_DEBOUNCE_MILLIS = 1_000L
    }

    class Factory(
        private val repository: JellyfinRepository,
        private val session: AuthSession,
        private val itemId: String,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PlaybackViewModel(
            repository, session, itemId, context.applicationContext
        ) as T
    }
}

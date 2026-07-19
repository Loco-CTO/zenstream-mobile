package com.zenstream.zenstreammobile.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zenstream.zenstreammobile.data.JellyfinRepository
import com.zenstream.zenstreammobile.data.playbackUrl
import com.zenstream.zenstreammobile.data.parseWebVttCues
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.model.PlaybackData
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlayerEngine
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
) {
    val activeCues: List<SubtitleCue>
        get() = subtitleCues.filter { cue ->
            val time = engine.positionSeconds + subtitleOffset
            time >= cue.startSeconds && time < cue.endSeconds
        }
}

class PlaybackViewModel(
    private val repository: JellyfinRepository,
    private val session: AuthSession,
    private val orchestratorUrl: String?,
    private val itemId: String,
    private val appContext: Context,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlaybackUiState(itemId = itemId))
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()
    private var playbackEngine: PlaybackEngine? = null
    private var engineJob: Job? = null
    private var progressJob: Job? = null
    private var recovered = false

    init {
        viewModelScope.launch {
            val engineType = repository.playerEngine.first()
            val subtitleStyle = repository.loadSubtitleStyle(session, orchestratorUrl)
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
        createEngine(PlayerEngine.MEDIA3)
        _uiState.value = _uiState.value.copy(engineType = PlayerEngine.MEDIA3, error = null)
        viewModelScope.launch { repository.savePlayerEngine(PlayerEngine.MEDIA3) }
        val playback = _uiState.value.playback
        if (playback != null) {
            playbackEngine?.prepare(
                playbackUrl(session, itemId, playback.source, _uiState.value.selectedQuality, (_uiState.value.engine.positionSeconds * 10_000_000).toLong()),
                _uiState.value.engine.positionSeconds,
            )
        }
    }

    private fun loadPlayback(options: PlaybackOptions = PlaybackOptions()) {
        viewModelScope.launch {
            val currentPosition = _uiState.value.engine.positionSeconds
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            runCatching {
                repository.playback(
                    session,
                    itemId,
                    options.copy(
                        startTimeTicks = if (currentPosition > 0) (currentPosition * 10_000_000).toLong() else options.startTimeTicks,
                    ),
                )
            }.onSuccess { data ->
                val selectedAudio = options.audioStreamIndex
                    ?: data.audio.firstOrNull { it.isDefault }?.index
                    ?: data.audio.firstOrNull()?.index
                val selectedSubtitle = _uiState.value.selectedSubtitle
                    ?: data.subtitles.firstOrNull { it.isDefault }?.index
                val start = if (currentPosition > 0) currentPosition else data.item.playbackPositionTicks.orZero() / 10_000_000.0
                val bitrate = options.maxStreamingBitrate ?: 0
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    itemName = data.item.name,
                    playback = data,
                    selectedAudio = selectedAudio,
                    selectedSubtitle = selectedSubtitle,
                    selectedQuality = bitrate,
                    error = null,
                )
                playbackEngine?.prepare(
                    com.zenstream.zenstreammobile.data.playbackUrl(session, itemId, data.source, bitrate, (start * 10_000_000).toLong()),
                    start,
                )
                loadSubtitle()
                startProgressReporting()
            }.onFailure {
                _uiState.value = _uiState.value.copy(loading = false, error = it.message ?: "Playback failed")
            }
        }
    }

    private fun Long?.orZero() = this ?: 0L

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
        val state = _uiState.value.engine
        if (state.positionSeconds > 0) {
            runCatching { repository.reportPlayback(session, itemId, state.positionSeconds, !state.isPlaying) }
        }
    }

    fun togglePlay() {
        if (_uiState.value.engine.isPlaying) playbackEngine?.pause() else playbackEngine?.play()
    }

    fun seekBy(deltaSeconds: Double) {
        val state = _uiState.value.engine
        playbackEngine?.seekTo((state.positionSeconds + deltaSeconds).coerceIn(0.0, state.durationSeconds.takeIf { it > 0 } ?: Double.MAX_VALUE))
    }

    fun seekTo(positionSeconds: Double) = playbackEngine?.seekTo(positionSeconds)
    fun setVolume(value: Float) = playbackEngine?.setVolume(value)
    fun setMuted(value: Boolean) = playbackEngine?.setMuted(value)
    fun setSpeed(value: Float) = playbackEngine?.setSpeed(value)

    fun chooseQuality(value: Int) {
        val current = _uiState.value.engine.positionSeconds
        loadPlayback(PlaybackOptions(maxStreamingBitrate = value.takeIf { it > 0 }, startTimeTicks = (current * 10_000_000).toLong()))
    }

    fun chooseAudio(stream: MediaStream) {
        val current = _uiState.value.engine.positionSeconds
        _uiState.value = _uiState.value.copy(selectedAudio = stream.index)
        loadPlayback(PlaybackOptions(mediaSourceId = _uiState.value.playback?.source?.id, audioStreamIndex = stream.index, startTimeTicks = (current * 10_000_000).toLong()))
    }

    fun chooseSubtitle(streamIndex: Int?) {
        _uiState.value = _uiState.value.copy(selectedSubtitle = streamIndex, subtitleCues = emptyList())
        loadSubtitle()
    }

    private fun loadSubtitle() {
        val selected = _uiState.value.selectedSubtitle ?: return
        val playback = _uiState.value.playback ?: return
        viewModelScope.launch {
            runCatching { repository.subtitleWebVtt(session, itemId, playback.source.id, selected) }
                .onSuccess { _uiState.value = _uiState.value.copy(subtitleCues = parseWebVttCues(it)) }
        }
    }

    fun setSubtitleOffset(value: Double) { _uiState.value = _uiState.value.copy(subtitleOffset = value) }

    fun updateSubtitleStyle(change: SubtitleStyle.() -> SubtitleStyle) {
        val next = change(_uiState.value.subtitleStyle)
        _uiState.value = _uiState.value.copy(subtitleStyle = next)
        viewModelScope.launch { repository.saveSubtitleStyle(session, orchestratorUrl, next) }
    }

    fun onPause() {
        viewModelScope.launch { reportProgress() }
    }

    override fun onCleared() {
        viewModelScope.launch { reportProgress() }
        progressJob?.cancel()
        engineJob?.cancel()
        playbackEngine?.release()
        playbackEngine = null
        super.onCleared()
    }

    class Factory(
        private val repository: JellyfinRepository,
        private val session: AuthSession,
        private val orchestratorUrl: String?,
        private val itemId: String,
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PlaybackViewModel(
            repository, session, orchestratorUrl, itemId, context.applicationContext
        ) as T
    }
}

package com.zenstream.zenstreammobile.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.activeSubtitleCues
import com.zenstream.zenstreammobile.data.isCurrentSubtitleRequest
import com.zenstream.zenstreammobile.data.parseWebVttCues
import com.zenstream.zenstreammobile.data.playbackLocalPositionSeconds
import com.zenstream.zenstreammobile.data.playbackMimeType
import com.zenstream.zenstreammobile.data.playbackStreamStartPositionSeconds
import com.zenstream.zenstreammobile.data.playbackUrl
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.model.PlaybackData
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlaybackSegment
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SubtitleCue
import com.zenstream.zenstreammobile.model.SubtitleStyle
import com.zenstream.zenstreammobile.ui.player.EngineState
import com.zenstream.zenstreammobile.ui.player.PlaybackEngine
import com.zenstream.zenstreammobile.ui.player.createPlaybackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

internal fun shouldClearPlayedOnPlaybackStart(
    isPlaying: Boolean,
    played: Boolean,
    resetAlreadyRequested: Boolean,
): Boolean = isPlaying && played && !resetAlreadyRequested

internal fun selectSubtitleTrack(
    currentTrack: Int?,
    selectionInitialized: Boolean,
    subtitles: List<MediaStream>,
): Int? {
    if (selectionInitialized) {
        return currentTrack?.takeIf { selected -> subtitles.any { it.index == selected } }
    }
    return currentTrack
        ?: subtitles.firstOrNull { it.isDefault }?.index
        ?: subtitles.firstOrNull()?.index
}

private data class PlaybackProgressSnapshot(
    val positionSeconds: Double,
	val durationSeconds: Double,
    val paused: Boolean,
    val sessionId: String?,
    val playbackGeneration: Long,
)

private const val PLAYBACK_TAG = "ZenStreamPlayback"
private const val TRICKPLAY_MANIFEST_ATTEMPTS = 12
private const val TRICKPLAY_MANIFEST_RETRY_MILLIS = 1_000L

private fun redactPlaybackUrl(value: String?): String =
    value.orEmpty().replace(Regex("(?i)([?&]access=)[^&\\s\\\"']+"), "$1<redacted>")

class PlaybackViewModel(
    private val repository: CatalogRepository,
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
    private val progressReportingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackLoadJob: Job? = null
    private var trickplayJob: Job? = null
    private var subtitleJob: Job? = null
    private var playbackGeneration = 0L
    private var subtitleGeneration = 0L
    private var mediaOriginSeconds = 0.0
    private var recovered = false
    private var playedResetRequested = false
    private var subtitleSelectionInitialized = false

    init {
        viewModelScope.launch {
            val engineType = repository.playerEngine.first()
            val subtitleStyle = repository.loadSubtitleStyle()
            _uiState.value =
                _uiState.value.copy(engineType = engineType, subtitleStyle = subtitleStyle)
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
                if (state.error != null) {
                    Log.w(
                        PLAYBACK_TAG,
                        "engine error item=$itemId mode=${_uiState.value.playback?.mode} sessionId=${_uiState.value.playback?.sessionId} error=${state.error} recovered=$recovered",
                    )
                }
                clearPlayedOnPlaybackStart(state)
                if (state.error != null && !recovered &&
                    (_uiState.value.playback?.mode == null ||
                        _uiState.value.playback?.mode == "direct")) {
                    recovered = true
                    Log.w(
                        PLAYBACK_TAG,
                        "performing single video-transcode recovery item=$itemId previousMode=${_uiState.value.playback?.mode}",
                    )
                    loadPlayback(
                        PlaybackOptions(
                            requestedMode = "video-transcode",
                            maxStreamingBitrate = 1_000_000
                        )
                    )
                }
            }
        }
    }

    private fun clearPlayedOnPlaybackStart(state: EngineState) {
        val playback = _uiState.value.playback ?: return
        if (!shouldClearPlayedOnPlaybackStart(
                state.isPlaying,
                playback.item.played,
                playedResetRequested
            )
        ) return

        // Set this before launching so the engine ticker cannot enqueue duplicate
        // DELETE requests while the first request is in flight.
        playedResetRequested = true
        viewModelScope.launch {
            runCatching { repository.setPlayed(session, itemId, false) }
                .onSuccess {
                    val current = _uiState.value.playback
                    if (current?.item?.id == itemId) {
                        _uiState.value = _uiState.value.copy(
                            playback = current.copy(item = current.item.copy(played = false)),
                        )
                    }
                }
                .onFailure {
                    // Match the web player: a failed reset may be retried on the
                    // next play-state update.
                    playedResetRequested = false
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
                playbackUrl(
                    session,
                    itemId,
                    playback.source,
                ),
                currentPosition,
                playbackMimeType(playback.source, _uiState.value.selectedQuality),
            )
        }
    }

    private fun loadPlayback(options: PlaybackOptions = PlaybackOptions()) {
        val loadGeneration = ++playbackGeneration
        progressFlushJob?.cancel()
        playbackLoadJob?.cancel()
        trickplayJob?.cancel()
        playbackLoadJob = viewModelScope.launch {
            val hasCurrentPlayback = _uiState.value.playback != null
            val currentPosition = currentPlayerPositionSeconds()
            val requestedStartSeconds = if (hasCurrentPlayback) {
                currentPosition
            } else {
                options.startPositionSeconds
            }.coerceAtLeast(0.0)
            val requestOptions = options.copy(
                startPositionSeconds = if (hasCurrentPlayback) requestedStartSeconds else options.startPositionSeconds,
            )
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            val data = runCatching {
                repository.playback(session, itemId, requestOptions)
            }.getOrElse {
                Log.e(PLAYBACK_TAG, "playback negotiation failed item=$itemId error=${it.message}")
                if (loadGeneration == playbackGeneration) {
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = it.message ?: "Playback failed"
                    )
                }
                return@launch
            }
            if (loadGeneration != playbackGeneration) return@launch
            Log.i(
                PLAYBACK_TAG,
                "playback data item=$itemId mode=${data.mode} state=${data.sessionState} sessionId=${data.sessionId} url=${redactPlaybackUrl(data.url)}",
            )

            val selectedAudio = requestOptions.audioStreamId
                ?: data.audioTracks.firstOrNull { it.isDefault }?.index
                ?: data.audioTracks.firstOrNull()?.index
            val selectedSubtitle = selectSubtitleTrack(
                currentTrack = _uiState.value.selectedSubtitle,
                selectionInitialized = subtitleSelectionInitialized,
                subtitles = data.subtitles,
            )
            val requestedOrResumeStartSeconds =
                if (hasCurrentPlayback || requestOptions.startPositionSeconds > 0) {
                    requestedStartSeconds
                } else {
                    data.item.playbackPositionTicks.orZero() / 10_000_000.0
                }
            val sourceOriginSeconds = 0.0
            val localStartSeconds = requestedOrResumeStartSeconds
            val bitrate = requestOptions.maxStreamingBitrate ?: 0
            mediaOriginSeconds = sourceOriginSeconds
            val previousTrickplay = _uiState.value.playback?.source?.trickplay
            val source = if (data.source.trickplay != null || previousTrickplay == null) {
                data.source
            } else {
                data.source.copy(trickplay = previousTrickplay)
            }
            val playbackData = data.copy(source = source)
            val subtitleLoadGeneration = ++subtitleGeneration
            subtitleJob?.cancel()
            _uiState.value = _uiState.value.copy(
                loading = false,
                itemName = playbackData.item.name,
                playback = playbackData,
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
                playbackUrl(
                    session,
                    itemId,
                    playbackData.source,
                ),
                localStartSeconds,
                playbackMimeType(playbackData.source, bitrate),
            )
            loadSubtitle(loadGeneration, subtitleLoadGeneration)
            startProgressReporting()
            loadTrickplay(loadGeneration, playbackData.source.id, playbackData.source.trickplay == null)
        }
    }

    private fun loadTrickplay(loadGeneration: Long, sourceId: String?, shouldLoad: Boolean) {
        if (!shouldLoad) return
        trickplayJob = viewModelScope.launch {
            repeat(TRICKPLAY_MANIFEST_ATTEMPTS) { attempt ->
                val manifest = runCatching {
                    repository.trickplay(session, itemId, sourceId)
                }.getOrNull()
                if (loadGeneration != playbackGeneration) return@launch
                if (manifest?.state == "ready" && manifest.sheets.isNotEmpty()) {
                    val current = _uiState.value.playback ?: return@launch
                    if (current.source.id == manifest.sourceId || sourceId == null) {
                        _uiState.value = _uiState.value.copy(
                            playback = current.copy(source = current.source.copy(trickplay = manifest)),
                        )
                    }
                    return@launch
                }
                if (manifest?.state !in setOf("queued", "generating") ||
                    attempt == TRICKPLAY_MANIFEST_ATTEMPTS - 1
                ) return@launch
                delay(TRICKPLAY_MANIFEST_RETRY_MILLIS)
            }
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
                snapshot.sessionId,
				snapshot.durationSeconds,
            )
        }
    }

    private fun playbackProgressSnapshot(): PlaybackProgressSnapshot? {
        val state = _uiState.value.engine
        val position = currentPlayerPositionSeconds()
        return position.takeIf { it > 0 }?.let {
            PlaybackProgressSnapshot(
                positionSeconds = it,
				durationSeconds = state.durationSeconds,
                paused = !state.isPlaying,
                sessionId = _uiState.value.playback?.sessionId,
                playbackGeneration = playbackGeneration,
            )
        }
    }

    fun togglePlay() {
        if (_uiState.value.engine.isPlaying) playbackEngine?.pause() else playbackEngine?.play()
    }

    fun seekBy(deltaSeconds: Double) {
        val state = _uiState.value.engine
        playbackEngine?.seekTo(
            (currentPlayerPositionSeconds() + deltaSeconds).coerceIn(
                0.0,
                state.durationSeconds.takeIf { it > 0 } ?: Double.MAX_VALUE))
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
        loadPlayback(
            PlaybackOptions(
                sourceId = _uiState.value.playback?.source?.id,
                audioStreamId = stream.index
            )
        )
    }

    fun chooseSubtitle(streamIndex: Int?) {
        subtitleJob?.cancel()
        val requestGeneration = ++subtitleGeneration
        subtitleSelectionInitialized = true
        _uiState.value =
            _uiState.value.copy(selectedSubtitle = streamIndex, subtitleCues = emptyList())
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

    fun setSubtitleOffset(value: Double) {
        _uiState.value = _uiState.value.copy(subtitleOffset = value)
    }

    fun updateSubtitleStyle(change: SubtitleStyle.() -> SubtitleStyle) {
        val next = change(_uiState.value.subtitleStyle)
        _uiState.value = _uiState.value.copy(subtitleStyle = next)
        viewModelScope.launch { repository.saveSubtitleStyle(next) }
    }

    fun flushProgress() {
        val now = SystemClock.elapsedRealtime()
        if (lastProgressFlushAt != 0L && now - lastProgressFlushAt < PROGRESS_FLUSH_DEBOUNCE_MILLIS) return
        lastProgressFlushAt = now
        val snapshot = playbackProgressSnapshot()
        progressFlushJob?.cancel()
        if (snapshot != null) {
            progressFlushJob = progressReportingScope.launch { reportProgress(snapshot) }
        }
    }

    fun onPause() = flushProgress()

    override fun onCleared() {
        progressJob?.cancel()
        progressFlushJob?.let { job ->
            job.invokeOnCompletion { progressReportingScope.cancel() }
        } ?: progressReportingScope.cancel()
        playbackLoadJob?.cancel()
        trickplayJob?.cancel()
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
        private val repository: CatalogRepository,
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

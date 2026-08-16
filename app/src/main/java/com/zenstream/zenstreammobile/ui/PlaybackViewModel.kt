package com.zenstream.zenstreammobile.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.PlaybackPreference
import com.zenstream.zenstreammobile.data.SyncplayManager
import com.zenstream.zenstreammobile.data.activeSubtitleCues
import com.zenstream.zenstreammobile.data.isCurrentSubtitleRequest
import com.zenstream.zenstreammobile.data.parseWebVttCues
import com.zenstream.zenstreammobile.data.playbackMimeType
import com.zenstream.zenstreammobile.data.playbackUrl
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.model.PlaybackData
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlaybackSegment
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SubtitleCue
import com.zenstream.zenstreammobile.model.SubtitleStyle
import com.zenstream.zenstreammobile.model.SyncplayGroup
import com.zenstream.zenstreammobile.model.ViewerCommandAck
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
    val showDebugIcon: Boolean = false,
    val engine: EngineState = EngineState(),
    val selectedAudio: Int? = null,
    val selectedSubtitle: Int? = null,
    val selectedQuality: Int = 0,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val subtitleCues: List<SubtitleCue> = emptyList(),
    val subtitleOffset: Double = 0.0,
    val mediaOriginSeconds: Double = 0.0,
    val segments: List<PlaybackSegment> = emptyList(),
    val previousEpisode: MediaItem? = null,
    val nextEpisode: MediaItem? = null,
    val episodeNeighborsLoaded: Boolean = false,
    val closeRequested: Boolean = false,
    val syncplaySettling: Boolean = false,
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

    fun activeSegmentAt(positionSeconds: Double): PlaybackSegment? = segments.firstOrNull {
        positionSeconds >= it.startSeconds && positionSeconds < it.endSeconds
    }
}

internal fun shouldClearPlayedOnPlaybackStart(
    isPlaying: Boolean,
    played: Boolean,
    resetAlreadyRequested: Boolean,
): Boolean = isPlaying && played && !resetAlreadyRequested

internal fun syncplayShouldAutoplay(
    room: SyncplayGroup?,
    participantId: String,
    itemId: String,
): Boolean {
    if (room?.itemId != itemId) return true
    val member = room.members.firstOrNull { it.participantId == participantId }
    return member?.watchingTogether != true
}

internal fun selectSubtitleTrack(
    currentTrack: Int?,
    selectionInitialized: Boolean,
    subtitles: List<MediaStream>,
    preferredLanguage: String? = null,
): Int? {
    if (selectionInitialized) {
        return currentTrack?.takeIf { selected -> subtitles.any { it.index == selected } }
    }
    return currentTrack ?: preferredSubtitleIndex(subtitles, preferredLanguage)
}

internal fun shouldHandlePlaybackCompletion(
    ended: Boolean,
    transitionInProgress: Boolean,
    handledCompletionGeneration: Long,
    playbackGeneration: Long,
): Boolean = ended && !transitionInProgress && handledCompletionGeneration != playbackGeneration

internal fun shouldWaitForEpisodeNeighbors(episodeNeighborsLoaded: Boolean): Boolean =
    !episodeNeighborsLoaded

internal fun nextUpFallbackItem(items: List<MediaItem>, currentItemId: String): MediaItem? =
    items.firstOrNull {
        it.id != currentItemId
    }

private data class PlaybackProgressSnapshot(
    val itemId: String,
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
    initialItemId: String,
    private val appContext: Context,
    private val initialAudioStreamId: Int? = null,
    private val initialSubtitleStreamIndex: Int? = null,
    private val hasInitialSubtitleSelection: Boolean = false,
    private val syncplay: SyncplayManager? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlaybackUiState(itemId = initialItemId))
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()
    private var playbackEngine: PlaybackEngine? = null
    private var engineJob: Job? = null
    private var progressJob: Job? = null
    private var viewerHeartbeatJob: Job? = null
    private var progressFlushJob: Job? = null
    private val progressReportingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackLoadJob: Job? = null
    private var trickplayJob: Job? = null
    private var subtitleJob: Job? = null
    private var episodeNeighborsJob: Job? = null
    private var nextUpFallbackJob: Job? = null
    private var playbackGeneration = 0L
    private var subtitleGeneration = 0L
    private var mediaOriginSeconds = 0.0
    private var recovered = false
    private var playedResetRequested = false
    private var subtitleSelectionInitialized = false
    private var currentItemId = initialItemId
    private var handledCompletionGeneration = -1L
    private var pendingCompletionGeneration: Long? = null
    private var nextUpFallbackGeneration: Long? = null
    private var transitionInProgress = false
    private var syncplayTimelineKey: String? = null
    private var playbackPreference: PlaybackPreference? = null
    private val handledViewerCommands = mutableSetOf<String>()
    private val viewerCommandAcks = mutableListOf<ViewerCommandAck>()

    init {
        subtitleSelectionInitialized = hasInitialSubtitleSelection
        viewModelScope.launch {
            val engineType = repository.playerEngine.first()
            val subtitleStyle = repository.loadSubtitleStyle()
            playbackPreference = runCatching { repository.loadPlaybackPreference() }.getOrNull()
            _uiState.value =
                _uiState.value.copy(
                    engineType = engineType,
                    subtitleStyle = subtitleStyle,
                    selectedSubtitle = initialSubtitleStreamIndex,
                )
            createEngine(engineType)
            val preferredAudioStreamId =
                if (initialAudioStreamId != null || playbackPreference?.audioLanguage == null) {
                    null
                } else {
                    runCatching { repository.playbackSource(session, currentItemId) }
                        .getOrNull()
                        ?.mediaStreams
                        ?.filter { it.type.equals("audio", true) }
                        ?.let { preferredTrackIndex(it, playbackPreference?.audioLanguage) }
                }
            loadPlayback(
                PlaybackOptions(audioStreamId = initialAudioStreamId ?: preferredAudioStreamId)
            )
        }
        viewModelScope.launch {
            repository.showDebugIcon.collectLatest { enabled ->
                _uiState.value = _uiState.value.copy(showDebugIcon = enabled)
            }
        }
    }

    private fun createEngine(type: PlayerEngine) {
        engineJob?.cancel()
        playbackEngine?.release()
        playbackEngine = createPlaybackEngine(type, appContext)
        engineJob = viewModelScope.launch {
            playbackEngine?.state?.collectLatest { state ->
                _uiState.value = _uiState.value.copy(engine = state, error = state.error)
                val room = syncplay?.state?.value?.active
                if (
                    _uiState.value.syncplaySettling &&
                        room?.itemId == currentItemId &&
                        state.ready &&
                        !state.isBuffering &&
                        kotlin.math.abs(
                            state.positionSeconds -
                                syncplayTimelineTarget(room, syncplay.serverNow()).positionSeconds
                        ) <= 1.5
                ) {
                    _uiState.value = _uiState.value.copy(syncplaySettling = false)
                }
                if (state.error != null) {
                    Log.w(
                        PLAYBACK_TAG,
                        "engine error item=$currentItemId mode=${_uiState.value.playback?.mode} sessionId=${_uiState.value.playback?.sessionId} error=${state.error} recovered=$recovered",
                    )
                }
                clearPlayedOnPlaybackStart(state)
                if (
                    state.error != null &&
                        !recovered &&
                        (_uiState.value.playback?.mode == null ||
                            _uiState.value.playback?.mode == "direct")
                ) {
                    recovered = true
                    Log.w(
                        PLAYBACK_TAG,
                        "performing single video-transcode recovery item=$currentItemId previousMode=${_uiState.value.playback?.mode}",
                    )
                    loadPlayback(
                        PlaybackOptions(
                            requestedMode = "video-transcode",
                            maxStreamingBitrate = 1_000_000,
                            sourceId = _uiState.value.playback?.source?.id,
                            audioStreamId = _uiState.value.selectedAudio,
                        )
                    )
                }
                if (state.ended) onPlaybackEnded()
            }
        }
    }

    private fun clearPlayedOnPlaybackStart(state: EngineState) {
        val playback = _uiState.value.playback ?: return
        if (
            !shouldClearPlayedOnPlaybackStart(
                state.isPlaying,
                playback.item.played,
                playedResetRequested,
            )
        )
            return

        // Set this before launching so the engine ticker cannot enqueue duplicate
        // DELETE requests while the first request is in flight.
        playedResetRequested = true
        viewModelScope.launch {
            runCatching { repository.setPlayed(session, playback.item.id, false) }
                .onSuccess {
                    val current = _uiState.value.playback
                    if (current?.item?.id == playback.item.id) {
                        _uiState.value =
                            _uiState.value.copy(
                                playback = current.copy(item = current.item.copy(played = false))
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

    fun createView(context: Context): View? =
        runCatching {
                playbackEngine?.createView(context)
            }
            .getOrElse {
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
                    currentItemId,
                    playback.source,
                ),
                currentPosition,
                playbackMimeType(playback.source, _uiState.value.selectedQuality),
                syncplayShouldAutoplayFor(currentItemId),
            )
        }
    }

    private fun syncplayShouldAutoplayFor(itemId: String): Boolean =
        syncplayShouldAutoplay(
            syncplay?.state?.value?.active,
            syncplay?.state?.value?.participantId.orEmpty(),
            itemId,
        )

    private fun loadPlayback(options: PlaybackOptions = PlaybackOptions()) {
        val loadGeneration = ++playbackGeneration
        progressFlushJob?.cancel()
        viewerHeartbeatJob?.cancel()
        playbackLoadJob?.cancel()
        trickplayJob?.cancel()
        playbackLoadJob = viewModelScope.launch {
            _uiState.value.playback?.let { outgoing ->
                if (outgoing.viewerSessionId != null) cancelPlaybackSession(outgoing)
            }
            val hasCurrentPlayback = _uiState.value.playback?.item?.id == currentItemId
            val currentPosition = currentPlayerPositionSeconds()
            val requestedStartSeconds =
                if (hasCurrentPlayback) {
                        currentPosition
                    } else {
                        options.startPositionSeconds
                    }
                    .coerceAtLeast(0.0)
            val requestOptions =
                options.copy(
                    engine = _uiState.value.engineType,
                    startPositionSeconds =
                        if (hasCurrentPlayback) requestedStartSeconds
                        else options.startPositionSeconds,
                )
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            val data =
                runCatching {
                        repository.playback(session, currentItemId, requestOptions)
                    }
                    .getOrElse {
                        Log.e(
                            PLAYBACK_TAG,
                            "playback negotiation failed item=$currentItemId error=${it.message}",
                        )
                        if (loadGeneration == playbackGeneration) {
                            _uiState.value =
                                _uiState.value.copy(
                                    loading = false,
                                    error = it.message ?: "Playback failed",
                                )
                            transitionInProgress = false
                        }
                        return@launch
                    }
            if (loadGeneration != playbackGeneration) return@launch
            Log.i(
                PLAYBACK_TAG,
                "playback data item=$currentItemId mode=${data.mode} state=${data.sessionState} sessionId=${data.sessionId} url=${redactPlaybackUrl(data.url)}",
            )

            val selectedAudio =
                requestOptions.audioStreamId
                    ?: preferredTrackIndex(
                        data.audioTracks,
                        playbackPreference?.audioLanguage,
                    )
            val selectedSubtitle =
                selectSubtitleTrack(
                    currentTrack = _uiState.value.selectedSubtitle,
                    selectionInitialized = subtitleSelectionInitialized,
                    subtitles = data.subtitles,
                    preferredLanguage = playbackPreference?.subtitleLanguage,
                )
            val requestedOrResumeStartSeconds =
                if (hasCurrentPlayback || requestOptions.startPositionSeconds > 0) {
                    requestedStartSeconds
                } else {
                    data.startPositionSeconds
                }
            val sourceOriginSeconds = 0.0
            val localStartSeconds = requestedOrResumeStartSeconds
            val bitrate = requestOptions.maxStreamingBitrate ?: 0
            mediaOriginSeconds = sourceOriginSeconds
            val previousTrickplay = _uiState.value.playback?.source?.trickplay
            val source =
                if (data.source.trickplay != null || previousTrickplay == null) {
                    data.source
                } else {
                    data.source.copy(trickplay = previousTrickplay)
                }
            val playbackData = data.copy(source = source)
            val streamUrl =
                runCatching {
                        playbackUrl(
                            session,
                            currentItemId,
                            playbackData.source,
                        )
                    }
                    .getOrElse { error ->
                        Log.e(
                            PLAYBACK_TAG,
                            "negotiated playback URL is unusable item=$currentItemId error=${error.message}",
                        )
                        _uiState.value =
                            _uiState.value.copy(
                                loading = false,
                                error =
                                    error.message
                                        ?: "Playback response did not include a usable stream URL",
                            )
                        transitionInProgress = false
                        return@launch
                    }
            val subtitleLoadGeneration = ++subtitleGeneration
            subtitleJob?.cancel()
            _uiState.value =
                _uiState.value.copy(
                    loading = false,
                    itemName = playbackData.item.name,
                    playback = playbackData,
                    selectedAudio = selectedAudio,
                    selectedSubtitle = selectedSubtitle,
                    selectedQuality = bitrate,
                    mediaOriginSeconds = sourceOriginSeconds,
                    subtitleCues = emptyList(),
                    segments =
                        data.segments.mapNotNull { segment ->
                            val start = segment.startSeconds - sourceOriginSeconds
                            val end = segment.endSeconds - sourceOriginSeconds
                            if (end <= 0.0) null
                            else
                                segment.copy(
                                    startSeconds = start.coerceAtLeast(0.0),
                                    endSeconds = end,
                                )
                        },
                    error = null,
                )
            playbackEngine?.prepare(
                streamUrl,
                localStartSeconds,
                playbackMimeType(playbackData.source, bitrate),
                syncplayShouldAutoplayFor(currentItemId),
            )
            handledCompletionGeneration = -1L
            transitionInProgress = false
            loadSubtitle(loadGeneration, subtitleLoadGeneration)
            startProgressReporting()
            startViewerHeartbeat()
            loadTrickplay(
                loadGeneration,
                playbackData.source.id,
                playbackData.source.trickplay == null,
            )
            loadEpisodeNeighbors(loadGeneration, playbackData.item)
        }
    }

    private fun loadEpisodeNeighbors(loadGeneration: Long, item: MediaItem) {
        episodeNeighborsJob?.cancel()
        _uiState.value =
            _uiState.value.copy(
                previousEpisode = null,
                nextEpisode = null,
                episodeNeighborsLoaded = false,
            )
        episodeNeighborsJob = viewModelScope.launch {
            val neighbors =
                runCatching { repository.episodeNeighbors(session, item) }
                    .onFailure { error ->
                        Log.w(
                            PLAYBACK_TAG,
                            "episode neighbor lookup failed item=${item.id} error=${error.message}",
                        )
                    }
                    .getOrNull()
            if (loadGeneration != playbackGeneration || currentItemId != item.id) return@launch
            Log.i(
                PLAYBACK_TAG,
                "episode neighbors item=${item.id} previous=${neighbors?.previous?.id ?: "none"} next=${neighbors?.next?.id ?: "none"}",
            )
            _uiState.value =
                _uiState.value.copy(
                    previousEpisode = neighbors?.previous,
                    nextEpisode = neighbors?.next,
                    episodeNeighborsLoaded = true,
                )
            if (pendingCompletionGeneration == loadGeneration) {
                advanceAfterEpisodeEnd()
            }
        }
    }

    private fun loadTrickplay(loadGeneration: Long, sourceId: String?, shouldLoad: Boolean) {
        if (!shouldLoad) return
        trickplayJob = viewModelScope.launch {
            repeat(TRICKPLAY_MANIFEST_ATTEMPTS) { attempt ->
                val manifest =
                    runCatching {
                            repository.trickplay(session, currentItemId, sourceId)
                        }
                        .getOrNull()
                if (loadGeneration != playbackGeneration) return@launch
                if (manifest?.state == "ready" && manifest.sheets.isNotEmpty()) {
                    val current = _uiState.value.playback ?: return@launch
                    if (current.source.id == manifest.sourceId || sourceId == null) {
                        _uiState.value =
                            _uiState.value.copy(
                                playback =
                                    current.copy(source = current.source.copy(trickplay = manifest))
                            )
                    }
                    return@launch
                }
                if (
                    manifest?.state !in setOf("queued", "generating") ||
                        attempt == TRICKPLAY_MANIFEST_ATTEMPTS - 1
                )
                    return@launch
                delay(TRICKPLAY_MANIFEST_RETRY_MILLIS)
            }
        }
    }

    private fun Long?.orZero() = this ?: 0L

    private fun ticks(seconds: Double): Long = (seconds.coerceAtLeast(0.0) * 10_000_000.0).toLong()

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

    private fun startViewerHeartbeat() {
        viewerHeartbeatJob?.cancel()
        val viewerSessionId = _uiState.value.playback?.viewerSessionId ?: return
        handledViewerCommands.clear()
        viewerCommandAcks.clear()
        viewerHeartbeatJob = viewModelScope.launch {
            while (true) {
                val playback = _uiState.value.playback
                if (playback?.viewerSessionId != viewerSessionId) return@launch
                val state = _uiState.value.engine
                val commandAcks = viewerCommandAcks.toList()
                viewerCommandAcks.clear()
                val result =
                    runCatching {
                        repository.heartbeatPlaybackViewer(
                            session,
                            viewerSessionId,
                            currentPlayerPositionSeconds(),
                            state.durationSeconds,
                            !state.isPlaying,
                            playback.sessionId,
                            commandAcks,
                        )
                    }.getOrNull()
                if (result == null) {
                    viewerCommandAcks.addAll(0, commandAcks)
                    delay(2_000)
                    continue
                }
                for (command in result.commands) {
                    if (!handledViewerCommands.add(command.id)) continue
                    var success = true
                    var error: String? = null
                    try {
                        when (command.action.lowercase()) {
                            "pause" -> playbackEngine?.pause()
                            "resume" -> playbackEngine?.play()
                            "stop" -> {
                                requestClose()
                                break
                            }
                            else -> error = "Unsupported playback command."
                        }
                        if (error != null) success = false
                    } catch (caught: Exception) {
                        success = false
                        error = caught.message ?: "Playback command failed."
                    }
                    viewerCommandAcks += ViewerCommandAck(command.id, success, error)
                }
                delay(2_000)
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
                snapshot.itemId,
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
        return position
            .takeIf { it > 0 }
            ?.let {
                PlaybackProgressSnapshot(
                    itemId = _uiState.value.playback?.item?.id ?: currentItemId,
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

    fun syncplayToggle(manager: SyncplayManager) {
        val room = manager.state.value.active
        if (room != null) {
            if (!manager.state.value.canControl(session.userId)) return
            viewModelScope.launch {
                manager.command(
                    if (_uiState.value.engine.isPlaying) "pause" else "play",
                    currentPlayerPositionSeconds(),
                    !_uiState.value.engine.isPlaying,
                    currentItemId,
                )
            }
        } else togglePlay()
    }

    fun syncplaySeekBy(manager: SyncplayManager, deltaSeconds: Double) =
        syncplaySeekTo(manager, currentPlayerPositionSeconds() + deltaSeconds)

    fun syncplaySeekTo(manager: SyncplayManager, positionSeconds: Double) {
        val room = manager.state.value.active
        val target = positionSeconds.coerceAtLeast(0.0)
        if (room != null) {
            if (!manager.state.value.canControl(session.userId)) return
            viewModelScope.launch {
                val shouldResume = room.playbackState == "playing" || room.resumeWhenReady
                manager.command("seek", target, shouldResume, currentItemId)
            }
        } else seekTo(target)
    }

    fun syncplayPrevious(manager: SyncplayManager) =
        syncplayEpisode(manager, _uiState.value.previousEpisode)

    fun syncplayNext(manager: SyncplayManager) =
        syncplayEpisode(manager, _uiState.value.nextEpisode)

    private fun syncplayEpisode(manager: SyncplayManager, target: MediaItem?) {
        if (target == null) return
        if (manager.state.value.active != null) {
            if (!manager.state.value.canControl(session.userId)) return
            viewModelScope.launch { manager.command("media", 0.0, true, target.id) }
        } else transitionTo(target)
    }

    fun applySyncplayRoom(room: SyncplayGroup, serverNow: Double) {
        val itemId = room.itemId ?: return
        Log.d(
            PLAYBACK_TAG,
            "Syncplay apply id=${room.id} revision=${room.revision} timeline=${room.timelineRevision} state=${room.playbackState} item=$itemId",
        )
        if (itemId != currentItemId) {
            _uiState.value = _uiState.value.copy(syncplaySettling = true)
            transitionToSyncplay(itemId, room.anchorPosition)
            return
        }
        val timeline = syncplayTimelineTarget(room, serverNow)
        val position = timeline.positionSeconds
        val current = currentPlayerPositionSeconds()
        val timelineKey = "${room.mediaGeneration}:${room.timelineRevision}"
        val newTimeline = syncplayTimelineKey != timelineKey
        if (newTimeline) {
            syncplayTimelineKey = timelineKey
            _uiState.value = _uiState.value.copy(syncplaySettling = true)
        }
        if (newTimeline || (timeline.shouldPlay && kotlin.math.abs(current - position) > 1.5))
            playbackEngine?.seekTo(position)
        if (timeline.shouldPlay) playbackEngine?.play() else playbackEngine?.pause()
    }

    private fun transitionToSyncplay(itemId: String, position: Double) {
        if (transitionInProgress || itemId == currentItemId) return
        transitionInProgress = true
        viewModelScope.launch {
            val outgoing = _uiState.value.playback
            invalidateActivePlaybackLoad()
            cancelPlaybackSession(outgoing)
            currentItemId = itemId
            _uiState.value =
                _uiState.value.copy(
                    loading = true,
                    itemId = itemId,
                    playback = null,
                    error = null,
                    syncplaySettling = true,
                )
            loadPlayback(PlaybackOptions(startPositionSeconds = position))
        }
    }

    fun pauseForBackground() {
        playbackEngine?.pause()
        _uiState.value = _uiState.value.copy(engine = _uiState.value.engine.copy(isPlaying = false))
    }

    fun seekBy(deltaSeconds: Double) {
        val state = _uiState.value.engine
        playbackEngine?.seekTo(
            (currentPlayerPositionSeconds() + deltaSeconds).coerceIn(
                0.0,
                state.durationSeconds.takeIf { it > 0 } ?: Double.MAX_VALUE,
            )
        )
    }

    fun seekTo(positionSeconds: Double) = playbackEngine?.seekTo(positionSeconds)

    fun skipSegment(segment: PlaybackSegment) = seekTo(segment.endSeconds)

    fun subtitlePositionSeconds(): Double =
        playbackEngine?.currentPositionSeconds() ?: _uiState.value.engine.positionSeconds

    fun setSpeed(value: Float) = playbackEngine?.setSpeed(value)

    fun playPreviousEpisode() = transitionTo(_uiState.value.previousEpisode)

    fun playNextEpisode() = transitionTo(_uiState.value.nextEpisode)

    private fun onPlaybackEnded() {
        if (
            !shouldHandlePlaybackCompletion(
                ended = true,
                transitionInProgress = transitionInProgress,
                handledCompletionGeneration = handledCompletionGeneration,
                playbackGeneration = playbackGeneration,
            )
        )
            return
        handledCompletionGeneration = playbackGeneration
        advanceAfterEpisodeEnd()
    }

    private fun advanceAfterEpisodeEnd() {
        if (shouldWaitForEpisodeNeighbors(_uiState.value.episodeNeighborsLoaded)) {
            pendingCompletionGeneration = playbackGeneration
            return
        }
        pendingCompletionGeneration = null
        val manager = syncplay
        if (manager?.state?.value?.active != null) {
            if (manager.state.value.active?.hostUserId != session.userId) return
            _uiState.value.nextEpisode?.let { target ->
                viewModelScope.launch { manager.command("media", 0.0, true, target.id) }
            } ?: playHomeNextUpAfterEpisodeEnd()
        } else {
            _uiState.value.nextEpisode?.let(::transitionTo) ?: playHomeNextUpAfterEpisodeEnd()
        }
    }

    private fun playHomeNextUpAfterEpisodeEnd() {
        val generation = playbackGeneration
        if (_uiState.value.playback?.item?.type != "Episode") {
            requestClose()
            return
        }
        if (nextUpFallbackGeneration == generation) return
        nextUpFallbackGeneration = generation
        nextUpFallbackJob?.cancel()
        nextUpFallbackJob = viewModelScope.launch {
            val target =
                runCatching { repository.homeNextUp(session) }
                    .getOrNull()
                    ?.let { nextUpFallbackItem(it, currentItemId) }
            if (generation != playbackGeneration || transitionInProgress) return@launch
            nextUpFallbackGeneration = null
            target?.let(::transitionTo) ?: requestClose()
        }
    }

    private fun transitionTo(target: MediaItem?) {
        if (target == null || transitionInProgress || target.id == currentItemId) return
        transitionInProgress = true
        pendingCompletionGeneration = null
        nextUpFallbackGeneration = null
        viewModelScope.launch {
            syncplay?.setWatchingTogether(false)
            val outgoing = _uiState.value.playback
            reportProgress(playbackProgressSnapshot())
            invalidateActivePlaybackLoad()
            cancelPlaybackSession(outgoing)
            currentItemId = target.id
            recovered = false
            playedResetRequested = false
            subtitleSelectionInitialized = false
            subtitleJob?.cancel()
            episodeNeighborsJob?.cancel()
            _uiState.value =
                _uiState.value.copy(
                    loading = true,
                    error = null,
                    itemId = target.id,
                    itemName = target.name,
                    playback = null,
                    selectedAudio = null,
                    selectedSubtitle = null,
                    selectedQuality = 0,
                    subtitleCues = emptyList(),
                    subtitleOffset = 0.0,
                    mediaOriginSeconds = 0.0,
                    segments = emptyList(),
                    previousEpisode = null,
                    nextEpisode = null,
                    episodeNeighborsLoaded = false,
                )
            loadPlayback()
        }
    }

    fun requestClose() {
        if (transitionInProgress || _uiState.value.closeRequested) return
        transitionInProgress = true
        pendingCompletionGeneration = null
        nextUpFallbackGeneration = null
        viewModelScope.launch {
            val outgoing = _uiState.value.playback
            reportProgress(playbackProgressSnapshot())
            invalidateActivePlaybackLoad()
            cancelPlaybackSession(outgoing)
            _uiState.value = _uiState.value.copy(closeRequested = true)
        }
    }

    private fun invalidateActivePlaybackLoad() {
        playbackGeneration++
        pendingCompletionGeneration = null
        nextUpFallbackGeneration = null
        progressFlushJob?.cancel()
        viewerHeartbeatJob?.cancel()
        playbackLoadJob?.cancel()
        trickplayJob?.cancel()
        subtitleJob?.cancel()
        episodeNeighborsJob?.cancel()
        nextUpFallbackJob?.cancel()
    }

    private suspend fun cancelPlaybackSession(playback: PlaybackData?) {
        if (playback == null) return
        var stopWorker = playback.mode != "direct"
        playback.viewerSessionId?.let { viewerId ->
            runCatching { repository.endPlaybackViewer(session, viewerId) }
                .onSuccess { result -> stopWorker = result.stopWorker }
                .onFailure { error ->
                    Log.w(
                        PLAYBACK_TAG,
                        "viewer session cancellation failed viewerSessionId=$viewerId error=${error.message}",
                    )
                }
        }
        val sessionId = playback.sessionId ?: return
        if (playback.mode == "direct" || !stopWorker) return
        runCatching { repository.cancelPlaybackSession(session, sessionId) }
            .onFailure { error ->
                Log.w(
                    PLAYBACK_TAG,
                    "playback session cancellation failed sessionId=$sessionId error=${error.message}",
                )
            }
    }

    fun chooseQuality(value: Int) {
        loadPlayback(
            PlaybackOptions(
                maxStreamingBitrate = value.takeIf { it > 0 },
                sourceId = _uiState.value.playback?.source?.id,
                audioStreamId = _uiState.value.selectedAudio,
            )
        )
    }

    fun chooseAudio(stream: MediaStream) {
        _uiState.value = _uiState.value.copy(selectedAudio = stream.index)
        loadPlayback(
            PlaybackOptions(
                sourceId = _uiState.value.playback?.source?.id,
                audioStreamId = stream.index,
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
            val body =
                runCatching {
                        repository.subtitleWebVtt(session, currentItemId, sourceId, selected)
                    }
                    .getOrNull() ?: return@launch
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
            )
                return@launch
            val cues =
                runCatching { parseWebVttCues(body) }
                    .getOrElse { error ->
                        Log.w(
                            PLAYBACK_TAG,
                            "subtitle parsing failed item=$currentItemId sourceId=$sourceId track=$selected error=${error.message}",
                        )
                        emptyList()
                    }
            _uiState.value = _uiState.value.copy(subtitleCues = cues)
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
        if (lastProgressFlushAt != 0L && now - lastProgressFlushAt < PROGRESS_FLUSH_DEBOUNCE_MILLIS)
            return
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
        viewerHeartbeatJob?.cancel()
        progressFlushJob?.let { job ->
            job.invokeOnCompletion { progressReportingScope.cancel() }
        } ?: progressReportingScope.cancel()
        playbackLoadJob?.cancel()
        trickplayJob?.cancel()
        subtitleJob?.cancel()
        episodeNeighborsJob?.cancel()
        engineJob?.cancel()
        playbackEngine?.release()
        playbackEngine = null
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
        private val initialAudioStreamId: Int? = null,
        private val initialSubtitleStreamIndex: Int? = null,
        private val hasInitialSubtitleSelection: Boolean = false,
        private val syncplay: SyncplayManager? = null,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PlaybackViewModel(
                repository,
                session,
                itemId,
                context.applicationContext,
                initialAudioStreamId,
                initialSubtitleStreamIndex,
                hasInitialSubtitleSelection,
                syncplay,
            )
                as T
    }
}

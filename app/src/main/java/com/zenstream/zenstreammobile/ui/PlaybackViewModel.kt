package com.zenstream.zenstreammobile.ui

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.CatalogException
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.PlaybackPreference
import com.zenstream.zenstreammobile.data.SyncplayManager
import com.zenstream.zenstreammobile.data.activeSubtitleCues
import com.zenstream.zenstreammobile.data.isCurrentSubtitleRequest
import com.zenstream.zenstreammobile.data.isStablePlaybackLeaseRenewal
import com.zenstream.zenstreammobile.data.parseWebVttCues
import com.zenstream.zenstreammobile.data.playbackMimeType
import com.zenstream.zenstreammobile.data.playbackUrlWithAccess
import com.zenstream.zenstreammobile.data.runSyncplayAction
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.model.MpvPlaybackSettings
import com.zenstream.zenstreammobile.model.PlaybackData
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlaybackSegment
import com.zenstream.zenstreammobile.model.PlaybackTimeDisplayMode
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SubtitleCue
import com.zenstream.zenstreammobile.model.SubtitleStyle
import com.zenstream.zenstreammobile.model.SyncplayGroup
import com.zenstream.zenstreammobile.model.TrickplayManifest
import com.zenstream.zenstreammobile.model.ViewerCommandAck
import com.zenstream.zenstreammobile.offline.OfflineNetworkPolicy
import com.zenstream.zenstreammobile.offline.PlaybackSourcePreference
import com.zenstream.zenstreammobile.offline.ResolvedPlaybackSource
import com.zenstream.zenstreammobile.offline.downloadedAudioOrdinal
import com.zenstream.zenstreammobile.offline.knownHdr
import com.zenstream.zenstreammobile.offline.offlinePlaybackCheckpointProfileId
import com.zenstream.zenstreammobile.offline.subtitleText
import com.zenstream.zenstreammobile.offline.videoData
import com.zenstream.zenstreammobile.ui.player.EngineState
import com.zenstream.zenstreammobile.ui.player.Media3DecoderControl
import com.zenstream.zenstreammobile.ui.player.Media3ServerRecovery
import com.zenstream.zenstreammobile.ui.player.Media3SourceContext
import com.zenstream.zenstreammobile.ui.player.PlaybackEngine
import com.zenstream.zenstreammobile.ui.player.VideoDecoderPreference
import com.zenstream.zenstreammobile.ui.player.createPlaybackEngine
import com.zenstream.zenstreammobile.ui.player.playbackRecoveryMode
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PlaybackUiState(
    val loading: Boolean = true,
    val local: Boolean = false,
    val canStream: Boolean = false,
    val engineInstance: Long = 0,
    val error: String? = null,
    val itemName: String = "",
    val itemId: String = "",
    val playback: PlaybackData? = null,
    val engineType: PlayerEngine = PlayerEngine.MPV,
    val timeDisplayMode: PlaybackTimeDisplayMode = PlaybackTimeDisplayMode.Remaining,
    val showDebugIcon: Boolean = false,
    val autoplayNextEpisode: Boolean = true,
    val watchHistoryEnabled: Boolean = true,
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
    val nextEpisodeUnavailable: Boolean = false,
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

internal fun shouldRecoverPlaybackError(mode: String?, alreadyRecovered: Boolean): Boolean =
    !alreadyRecovered && mode in setOf(null, "direct", "remux")

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

internal enum class EpisodeCompletionAction {
    WAIT_FOR_NEIGHBORS,
    PLAY_NEXT,
    CLOSE,
}

internal fun episodeCompletionAction(
    episodeNeighborsLoaded: Boolean,
    nextEpisode: MediaItem?,
    autoplayNextEpisode: Boolean = true,
): EpisodeCompletionAction =
    when {
        !episodeNeighborsLoaded -> EpisodeCompletionAction.WAIT_FOR_NEIGHBORS
        nextEpisode == null -> EpisodeCompletionAction.CLOSE
        !autoplayNextEpisode -> EpisodeCompletionAction.CLOSE
        else -> EpisodeCompletionAction.PLAY_NEXT
    }

internal fun isUsableTrickplayManifest(manifest: TrickplayManifest?): Boolean {
    if (manifest == null || !manifest.state.equals("ready", ignoreCase = true)) return false
    return manifest.frameWidth > 0 &&
        manifest.frameHeight > 0 &&
        manifest.intervalSeconds.isFinite() &&
        manifest.intervalSeconds > 0.0 &&
        manifest.columns > 0 &&
        manifest.rows > 0 &&
        manifest.frameCount > 0 &&
        manifest.sheets.isNotEmpty()
}

internal fun shouldLoadTrickplay(manifest: TrickplayManifest?): Boolean =
    !isUsableTrickplayManifest(manifest)

internal fun shouldApplyTrickplayManifest(
    loadGeneration: Long,
    playbackGeneration: Long,
    currentSourceId: String?,
    requestedSourceId: String?,
    manifest: TrickplayManifest?,
): Boolean {
    if (loadGeneration != playbackGeneration || !isUsableTrickplayManifest(manifest)) {
        return false
    }
    return (currentSourceId == null || manifest?.sourceId == currentSourceId) &&
        (requestedSourceId == null || manifest?.sourceId == requestedSourceId)
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
    private val session: AuthSession?,
    initialItemId: String,
    private val appContext: Context,
    private val initialAudioStreamId: Int? = null,
    private val initialSubtitleStreamIndex: Int? = null,
    private val hasInitialSubtitleSelection: Boolean = false,
    private val syncplay: SyncplayManager? = null,
    private val offlineProfileId: String? = null,
    private val offlineOnly: Boolean = false,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlaybackUiState(itemId = initialItemId))
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()
    private var playbackEngine: PlaybackEngine? = null
    private var resolvedSource: ResolvedPlaybackSource? = null
    private var streamRequested = false
    private var policyStopped = false
    private val remoteSession: AuthSession
        get() = requireNotNull(session) { "Sign in to stream this item." }

    private var engineJob: Job? = null
    private var progressJob: Job? = null
    private var viewerHeartbeatJob: Job? = null
    private var playbackAccessJob: Job? = null
    private var progressFlushJob: Job? = null
    private val progressReportingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playbackLoadJob: Job? = null
    private var trickplayJob: Job? = null
    private var subtitleJob: Job? = null
    private var episodeNeighborsJob: Job? = null
    private var playbackGeneration = 0L
    private var subtitleGeneration = 0L
    private var mediaOriginSeconds = 0.0
    private val media3ServerRecovery = Media3ServerRecovery()
    private var recovered = false
    private var playedResetRequested = false
    private var subtitleSelectionInitialized = false
    private var currentItemId = initialItemId
    private var handledCompletionGeneration = -1L
    private var pendingCompletionGeneration: Long? = null
    private var transitionInProgress = false
    private var syncplayTimelineKey: String? = null
    private var playbackPreference: PlaybackPreference? = null
    private val handledViewerCommands = mutableSetOf<String>()
    private val viewerCommandAcks = mutableListOf<ViewerCommandAck>()
    private val closeCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var closeCleanupJob: Job? = null
    private var backgrounded = false
    private var backgroundRecoveryJob: Job? = null
    private var backgroundRecoveryGeneration = 0L
    private var mpvPlaybackSettings = MpvPlaybackSettings()

    init {
        subtitleSelectionInitialized = hasInitialSubtitleSelection
        viewModelScope.launch {
            val engineType = repository.playerEngine.first()
            mpvPlaybackSettings =
                MpvPlaybackSettings(
                    videoOutput = repository.mpvVideoOutput.first(),
                    profile = repository.mpvVideoProfile.first(),
                    scaler = repository.mpvVideoScaler.first(),
                )
            val timeDisplayMode = repository.playbackTimeDisplayMode.first()
            val autoplayNextEpisode = repository.autoplayNextEpisode.first()
            val localRequested =
                offlineOnly ||
                    session == null ||
                    !OfflineNetworkPolicy.allowed ||
                    repository.offline?.let { runtime ->
                        com.zenstream.zenstreammobile.offline.OfflineRuntime.ENABLED &&
                            (offlineProfileId
                                    ?: runtime.connection.state.first { it.initialized }.profile)
                                ?.let {
                                    runtime.database
                                        .offline()
                                        .playbackCandidates(it, currentItemId, 1)
                                        .isNotEmpty()
                                } == true
                    } == true
            val watchHistoryEnabled =
                runCatching {
                        if (localRequested) repository.watchHistoryEnabled.first()
                        else repository.loadWatchHistoryPreference()
                    }
                    .getOrElse { repository.watchHistoryEnabled.first() }
            val subtitleStyle = repository.loadSubtitleStyle()
            playbackPreference =
                if (localRequested) null
                else runCatching { repository.loadPlaybackPreference() }.getOrNull()
            _uiState.value =
                _uiState.value.copy(
                    engineType = engineType,
                    timeDisplayMode = timeDisplayMode,
                    autoplayNextEpisode = autoplayNextEpisode,
                    watchHistoryEnabled = watchHistoryEnabled,
                    subtitleStyle = subtitleStyle,
                    selectedSubtitle = initialSubtitleStreamIndex,
                )
            createEngine(engineType)
            val preferredAudioStreamId =
                if (
                    localRequested ||
                        initialAudioStreamId != null ||
                        playbackPreference?.audioLanguage == null
                ) {
                    null
                } else {
                    runCatching { repository.playbackSource(remoteSession, currentItemId) }
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
        viewModelScope.launch {
            repository.autoplayNextEpisode.collectLatest { enabled ->
                _uiState.value = _uiState.value.copy(autoplayNextEpisode = enabled)
            }
        }
        viewModelScope.launch {
            repository.watchHistoryEnabled.collectLatest { enabled ->
                _uiState.value = _uiState.value.copy(watchHistoryEnabled = enabled)
            }
        }
        repository.offline?.let { runtime ->
            viewModelScope.launch {
                combine(runtime.connection.state, runtime.catalogRevision) { policy, _ -> policy }
                    .collect { policy ->
                        val local = resolvedSource as? ResolvedPlaybackSource.Local
                        if (
                            local != null &&
                                (policy.profile != local.profile.id ||
                                    runtime.database.offline().profile(local.profile.id)?.locked !=
                                        false)
                        ) {
                            playbackEngine?.stop()
                            requestClose()
                        } else if (
                            resolvedSource is ResolvedPlaybackSource.Remote &&
                                policy.mode ==
                                    com.zenstream.zenstreammobile.offline.ConnectionMode.OFFLINE
                        ) {
                            policyStopped = true
                            invalidateActivePlaybackLoad()
                            playbackEngine?.stop()
                            _uiState.value =
                                _uiState.value.copy(
                                    loading = false,
                                    error =
                                        appContext.getString(
                                            com.zenstream.zenstreammobile.R.string
                                                .offline_video_stream_paused
                                        ),
                                    canStream = false,
                                )
                        } else {
                            _uiState.value =
                                _uiState.value.copy(
                                    canStream = session != null && OfflineNetworkPolicy.allowed
                                )
                        }
                    }
            }
        }
    }

    private fun createEngine(type: PlayerEngine) {
        engineJob?.cancel()
        runCatching { playbackEngine?.release() }
        playbackEngine = createPlaybackEngine(type, appContext, mpvPlaybackSettings)
        engineJob = viewModelScope.launch {
            var lastMedia3Failure = 0L
            playbackEngine?.state?.collectLatest { state ->
                _uiState.value =
                    _uiState.value.copy(
                        engine = state,
                        error = if (policyStopped) _uiState.value.error else state.error,
                    )
                val manager = syncplay.takeUnless { _uiState.value.local }
                val room = manager?.state?.value?.active
                settleSyncplayTimelineIfReady(room, state, manager?.serverNow() ?: 0.0)
                if (state.error != null) {
                    Log.w(
                        PLAYBACK_TAG,
                        "engine error item=$currentItemId mode=${_uiState.value.playback?.mode} sessionId=${_uiState.value.playback?.sessionId} error=${state.error} recovered=$recovered",
                    )
                }
                clearPlayedOnPlaybackStart(state)
                val media3 = state.media3
                if (
                    state.error != null &&
                        _uiState.value.local &&
                        playbackEngine?.recoverLocal(state.errorTrackType) == true
                ) {
                    _uiState.value =
                        _uiState.value.copy(
                            error = null,
                            engineInstance = _uiState.value.engineInstance + 1,
                        )
                }
                if (
                    state.error != null &&
                        !_uiState.value.local &&
                        media3?.failureKind != null &&
                        media3.failureSequence > lastMedia3Failure
                ) {
                    lastMedia3Failure = media3.failureSequence
                    val nextMode =
                        media3ServerRecovery.nextMode(
                            identity =
                                "$currentItemId:${_uiState.value.playback?.source?.id.orEmpty()}",
                            currentMode = _uiState.value.playback?.mode,
                            audioFailure =
                                state.errorTrackType == androidx.media3.common.C.TRACK_TYPE_AUDIO,
                            kind = media3.failureKind,
                            hdr = media3.hdr,
                        )
                    if (nextMode != null) {
                        Log.i(PLAYBACK_TAG, "Media3 server recovery mode=$nextMode")
                        loadPlayback(
                            PlaybackOptions(
                                requestedMode = nextMode,
                                maxStreamingBitrate =
                                    _uiState.value.selectedQuality.takeIf { it > 0 },
                                sourceId = _uiState.value.playback?.source?.id,
                                audioStreamId = _uiState.value.selectedAudio,
                            ),
                            playWhenReadyOverride = media3.playWhenReady,
                        )
                    }
                }
                if (
                    _uiState.value.engineType != PlayerEngine.MEDIA3 &&
                        state.error != null &&
                        !_uiState.value.local &&
                        shouldRecoverPlaybackError(_uiState.value.playback?.mode, recovered)
                ) {
                    recovered = true
                    val recoveryMode = playbackRecoveryMode(state.errorTrackType)
                    Log.w(
                        PLAYBACK_TAG,
                        "performing single $recoveryMode recovery item=$currentItemId previousMode=${_uiState.value.playback?.mode}",
                    )
                    loadPlayback(
                        PlaybackOptions(
                            requestedMode = recoveryMode,
                            sourceId = _uiState.value.playback?.source?.id,
                            audioStreamId = _uiState.value.selectedAudio,
                        )
                    )
                }
                if (state.ended) onPlaybackEnded()
            }
        }
    }

    private fun configurePlaybackSource(
        data: PlaybackData,
        local: ResolvedPlaybackSource.Local?,
    ) {
        val videoStreams =
            data.source.mediaStreams.filter {
                it.type.equals("video", true) && !it.isAttachedPicture
            }
        val hdr =
            local?.knownHdr() == true ||
                videoStreams.any {
                    it.colorTransfer in setOf("smpte2084", "arib-std-b67") ||
                        it.codec in setOf("dvhe", "dvh1")
                }
        playbackEngine?.setKnownHdr(hdr)
        (playbackEngine as? Media3DecoderControl)?.configureSource(
            Media3SourceContext(
                identity =
                    if (local != null)
                        "${local.profile.id}:${local.asset.id}:${local.asset.sourceRevision}"
                    else "$currentItemId:${data.source.id.orEmpty()}",
                expectedVideo =
                    videoStreams.isNotEmpty() ||
                        data.item.type.orEmpty().lowercase() in setOf("movie", "episode"),
                hdr = hdr,
                mode = data.mode,
                audioStreamId = _uiState.value.selectedAudio,
            )
        )
    }

    private fun clearPlayedOnPlaybackStart(state: EngineState) {
        if (!_uiState.value.watchHistoryEnabled) return
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
        if (_uiState.value.local) {
            val source = resolvedSource as? ResolvedPlaybackSource.Local ?: return
            viewModelScope.launch {
                runCatching {
                        repository.offline
                            ?.playbackState
                            ?.setPlayed(
                                source.profile.id,
                                playback.item.id,
                                false,
                            )
                    }
                    .onSuccess {
                        val current = _uiState.value.playback
                        if (current?.item?.id == playback.item.id) {
                            _uiState.value =
                                _uiState.value.copy(
                                    playback =
                                        current.copy(
                                            item =
                                                current.item.copy(
                                                    played = false,
                                                    playedPercentage = 0.0,
                                                    playbackPositionTicks = 0,
                                                )
                                        )
                                )
                        }
                    }
                    .onFailure { playedResetRequested = false }
            }
            return
        }
        viewModelScope.launch {
            runCatching { repository.setPlayed(remoteSession, playback.item.id, false) }
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

    fun createView(context: Context): View? {
        return runCatching { playbackEngine?.createView(context) }
            .getOrElse {
                fallbackToMedia3()
                playbackEngine?.createView(context)
            }
    }

    private fun fallbackToMedia3() {
        if (_uiState.value.engineType == PlayerEngine.MEDIA3) return
        val currentPosition = currentPlayerPositionSeconds()
        createEngine(PlayerEngine.MEDIA3)
        _uiState.value = _uiState.value.copy(engineType = PlayerEngine.MEDIA3, error = null)
        if (!_uiState.value.local)
            viewModelScope.launch { repository.savePlayerEngine(PlayerEngine.MEDIA3) }
        val playback = _uiState.value.playback
        if (playback != null) {
            configurePlaybackSource(playback, resolvedSource as? ResolvedPlaybackSource.Local)
            playbackEngine?.prepare(
                requireNotNull(resolvedSource).uri,
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

    private fun loadPlayback(
        options: PlaybackOptions = PlaybackOptions(),
        playWhenReadyOverride: Boolean? = null,
    ) {
        policyStopped = false
        val loadGeneration = ++playbackGeneration
        progressFlushJob?.cancel()
        viewerHeartbeatJob?.cancel()
        playbackLoadJob?.cancel()
        playbackAccessJob?.cancel()
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
            val resolved =
                runCatching {
                        repository.resolvePlayback(
                            session,
                            currentItemId,
                            requestOptions,
                            preference =
                                if (streamRequested) PlaybackSourcePreference.STREAM
                                else if (offlineOnly || session == null)
                                    PlaybackSourcePreference.DOWNLOAD_ONLY
                                else PlaybackSourcePreference.PREFER_DOWNLOAD,
                            profileId = offlineProfileId,
                        )
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
                                    syncplaySettling = false,
                                    error = it.message ?: "Playback failed",
                                )
                            transitionInProgress = false
                        }
                        return@launch
                    }
            if (loadGeneration != playbackGeneration) {
                resolved.close()
                return@launch
            }
            val local = resolved as? ResolvedPlaybackSource.Local
            val data = local?.videoData() ?: resolved.data
            val outgoingSource = resolvedSource
            resolvedSource = resolved
            Log.i(
                PLAYBACK_TAG,
                "playback data item=$currentItemId mode=${data.mode} state=${data.sessionState} sessionId=${data.sessionId} url=${redactPlaybackUrl(data.url)}",
            )

            val selectedAudio =
                (if (local != null)
                    data.audioTracks
                        .getOrNull(
                            downloadedAudioOrdinal(
                                data,
                                org.json.JSONObject(local.asset.descriptor),
                            )
                        )
                        ?.index
                else requestOptions.audioStreamId)
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
                if (local != null || data.source.trickplay != null || previousTrickplay == null) {
                    data.source
                } else {
                    data.source.copy(trickplay = previousTrickplay)
                }
            val playbackData = data.copy(source = source)
            val streamUrl = resolved.uri
            val subtitleLoadGeneration = ++subtitleGeneration
            subtitleJob?.cancel()
            _uiState.value =
                _uiState.value.copy(
                    loading = false,
                    local = local != null,
                    canStream = session != null && OfflineNetworkPolicy.allowed,
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
                    previousEpisode = null,
                    nextEpisode = null,
                    episodeNeighborsLoaded = false,
                    nextEpisodeUnavailable = false,
                    error = null,
                )
            configurePlaybackSource(playbackData, local)
            playbackEngine?.prepare(
                streamUrl,
                localStartSeconds,
                if (local != null) playbackData.mimeType
                else playbackMimeType(playbackData.source, bitrate),
                playWhenReadyOverride ?: syncplayShouldAutoplayFor(currentItemId),
            )
            if (local != null)
                playbackEngine?.selectAudioTrack(
                    data.audioTracks.indexOfFirst { it.index == selectedAudio }.coerceAtLeast(0)
                )
            outgoingSource?.close()
            handledCompletionGeneration = -1L
            transitionInProgress = false
            loadSubtitle(loadGeneration, subtitleLoadGeneration)
            startProgressReporting()
            if (local == null) {
                startViewerHeartbeat()
                startPlaybackAccessRefresh(playbackData)
                loadTrickplay(
                    loadGeneration,
                    playbackData.source.id,
                    shouldLoadTrickplay(playbackData.source.trickplay),
                )
                loadEpisodeNeighbors(loadGeneration, playbackData.item)
            } else {
                episodeNeighborsJob?.cancel()
                val navigation =
                    runCatching {
                            repository.offline
                                ?.catalog
                                ?.episodeNavigation(
                                    local.profile.id,
                                    playbackData.item.id,
                                    local.bundle,
                                )
                        }
                        .onFailure { error ->
                            Log.w(
                                PLAYBACK_TAG,
                                "offline episode neighbor lookup failed item=${playbackData.item.id} error=${error.message}",
                            )
                        }
                        .getOrNull()
                if (loadGeneration == playbackGeneration && currentItemId == playbackData.item.id) {
                    _uiState.value =
                        _uiState.value.copy(
                            previousEpisode = navigation?.previous,
                            nextEpisode = navigation?.next,
                            episodeNeighborsLoaded = true,
                            nextEpisodeUnavailable = navigation?.nextUnavailable == true,
                        )
                    if (pendingCompletionGeneration == loadGeneration) advanceAfterEpisodeEnd()
                }
            }
        }
    }

    private fun loadEpisodeNeighbors(loadGeneration: Long, item: MediaItem) {
        episodeNeighborsJob?.cancel()
        _uiState.value =
            _uiState.value.copy(
                previousEpisode = null,
                nextEpisode = null,
                episodeNeighborsLoaded = false,
                nextEpisodeUnavailable = false,
            )
        episodeNeighborsJob = viewModelScope.launch {
            val neighbors =
                runCatching { repository.episodeNeighbors(remoteSession, item) }
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
                val result = runCatching {
                    repository.trickplay(remoteSession, currentItemId, sourceId)
                }
                result.exceptionOrNull()?.let { error ->
                    Log.w(
                        PLAYBACK_TAG,
                        "trickplay manifest request failed item=$currentItemId source=${sourceId ?: "auto"} attempt=${attempt + 1} error=${redactPlaybackUrl(error.message)}",
                    )
                }
                val manifest = result.getOrNull()
                if (loadGeneration != playbackGeneration) return@launch
                if (manifest != null && isUsableTrickplayManifest(manifest)) {
                    val current = _uiState.value.playback ?: return@launch
                    if (
                        shouldApplyTrickplayManifest(
                            loadGeneration = loadGeneration,
                            playbackGeneration = playbackGeneration,
                            currentSourceId = current.source.id,
                            requestedSourceId = sourceId,
                            manifest = manifest,
                        )
                    ) {
                        _uiState.value =
                            _uiState.value.copy(
                                playback =
                                    current.copy(source = current.source.copy(trickplay = manifest))
                            )
                        Log.i(
                            PLAYBACK_TAG,
                            "trickplay manifest accepted item=$currentItemId source=${manifest.sourceId} sheets=${manifest.sheets.size} firstSheet=${redactPlaybackUrl(manifest.sheets.firstOrNull()?.url)}",
                        )
                    } else {
                        Log.w(
                            PLAYBACK_TAG,
                            "trickplay manifest rejected item=$currentItemId source=${manifest.sourceId} expected=${current.source.id ?: sourceId ?: "auto"} generation=$loadGeneration/$playbackGeneration",
                        )
                    }
                    return@launch
                }
                if (manifest == null) {
                    Log.w(
                        PLAYBACK_TAG,
                        "trickplay manifest unavailable item=$currentItemId source=${sourceId ?: "auto"} attempt=${attempt + 1}",
                    )
                    return@launch
                }
                Log.i(
                    PLAYBACK_TAG,
                    "trickplay manifest pending or incomplete item=$currentItemId source=${manifest.sourceId} state=${manifest.state} sheets=${manifest.sheets.size} attempt=${attempt + 1}",
                )
                if (
                    manifest.state !in setOf("queued", "generating") ||
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

    private fun startPlaybackAccessRefresh(playback: PlaybackData) {
        playbackAccessJob?.cancel()
        val sourceId = playback.source.id ?: return
        var expiresInSeconds = playback.accessExpiresIn ?: return
        playbackAccessJob = viewModelScope.launch {
            while (true) {
                delay((expiresInSeconds * 1_000L - 60_000L).coerceAtLeast(30_000L))
                val current = _uiState.value.playback
                if (
                    current?.item?.id != currentItemId ||
                        current.source.id != sourceId ||
                        playbackGeneration <= 0L
                ) {
                    return@launch
                }
                val refreshed =
                    try {
                        repository.refreshPlaybackAccess(
                            remoteSession,
                            currentItemId,
                            sourceId,
                            current.sessionId,
                            current.playbackAccessMode,
                            current.playbackLeaseToken,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        Log.w(
                            PLAYBACK_TAG,
                            "playback access refresh failed item=$currentItemId error=${error.message}",
                        )
                        delay(30_000L)
                        continue
                    }
                val latest = _uiState.value.playback
                if (latest?.item?.id != currentItemId || latest.source.id != sourceId) {
                    return@launch
                }
                if (
                    isStablePlaybackLeaseRenewal(
                        latest.playbackAccessMode,
                        refreshed.playbackAccessMode,
                    )
                ) {
                    _uiState.value =
                        _uiState.value.copy(
                            playback = latest.copy(accessExpiresIn = refreshed.expiresInSeconds)
                        )
                    expiresInSeconds = refreshed.expiresInSeconds
                    continue
                }
                val refreshedTicket = refreshed.ticket
                if (refreshedTicket.isNullOrBlank()) {
                    Log.w(PLAYBACK_TAG, "legacy playback access refresh returned no ticket")
                    delay(30_000L)
                    continue
                }
                val position = currentPlayerPositionSeconds()
                val shouldPlay = _uiState.value.engine.isPlaying
                val nextUrl =
                    playbackUrlWithAccess(
                        remoteSession,
                        currentItemId,
                        latest.source,
                        refreshedTicket,
                    )
                val nextSource = latest.source.copy(url = nextUrl)
                val nextPlayback =
                    latest.copy(
                        source = nextSource,
                        url = nextUrl,
                        accessExpiresIn = refreshed.expiresInSeconds,
                    )
                _uiState.value = _uiState.value.copy(playback = nextPlayback)
                playbackEngine?.prepare(
                    nextUrl,
                    position,
                    playbackMimeType(nextSource, _uiState.value.selectedQuality),
                    shouldPlay,
                )
                expiresInSeconds = refreshed.expiresInSeconds
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
                val heartbeatResult = runCatching {
                    repository.heartbeatPlaybackViewer(
                        remoteSession,
                        viewerSessionId,
                        currentPlayerPositionSeconds(),
                        state.durationSeconds,
                        !state.isPlaying,
                        playback.sessionId,
                        commandAcks,
                    )
                }
                val result = heartbeatResult.getOrNull()
                if (result == null) {
                    val error = heartbeatResult.exceptionOrNull()
                    if (error is CatalogException && error.statusCode in setOf(401, 404, 410)) {
                        return@launch
                    }
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

    private suspend fun reportProgress(
        snapshot: PlaybackProgressSnapshot?,
        requireCurrentGeneration: Boolean = true,
    ) {
        if (snapshot == null) return
        if (requireCurrentGeneration && snapshot.playbackGeneration != playbackGeneration) return
        val localSource = resolvedSource as? ResolvedPlaybackSource.Local
        if (_uiState.value.local) {
            if (handledCompletionGeneration == snapshot.playbackGeneration) return
            val profileId =
                offlinePlaybackCheckpointProfileId(
                    isLocalPlayback = true,
                    localSourceProfileId = localSource?.profile?.id,
                    activeOfflineProfileId = offlineProfileId,
                ) ?: return
            runCatching {
                repository.offline
                    ?.playbackState
                    ?.checkpoint(
                        profileId,
                        snapshot.itemId,
                        snapshot.positionSeconds,
                        snapshot.durationSeconds,
                        _uiState.value.watchHistoryEnabled,
                    )
            }
            return
        }
        val profileId =
            offlinePlaybackCheckpointProfileId(
                isLocalPlayback = false,
                localSourceProfileId = localSource?.profile?.id,
                activeOfflineProfileId = offlineProfileId,
            )
        if (profileId != null) {
            runCatching {
                repository.offline
                    ?.playbackState
                    ?.checkpoint(
                        profileId,
                        snapshot.itemId,
                        snapshot.positionSeconds,
                        snapshot.durationSeconds,
                        _uiState.value.watchHistoryEnabled,
                    )
            }
        }
        if (!_uiState.value.watchHistoryEnabled) return
        runCatching {
            repository.reportPlayback(
                remoteSession,
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

    fun syncplayToggle(manager: SyncplayManager?) {
        if (manager == null || _uiState.value.local) {
            togglePlay()
            return
        }
        val room = manager.state.value.active
        if (room != null) {
            if (!manager.state.value.canControl(session?.userId.orEmpty())) return
            viewModelScope.launch {
                runSyncplayAction {
                    manager.command(
                        if (_uiState.value.engine.isPlaying) "pause" else "play",
                        currentPlayerPositionSeconds(),
                        !_uiState.value.engine.isPlaying,
                        currentItemId,
                    )
                }
            }
        } else togglePlay()
    }

    fun syncplaySeekBy(manager: SyncplayManager?, deltaSeconds: Double) =
        syncplaySeekTo(manager, currentPlayerPositionSeconds() + deltaSeconds)

    fun syncplaySeekTo(manager: SyncplayManager?, positionSeconds: Double) {
        if (manager == null || _uiState.value.local) {
            seekTo(positionSeconds)
            return
        }
        val room = manager.state.value.active
        val target = positionSeconds.coerceAtLeast(0.0)
        if (room != null) {
            if (!manager.state.value.canControl(session?.userId.orEmpty())) return
            viewModelScope.launch {
                val shouldResume = room.playbackState == "playing" || room.resumeWhenReady
                runSyncplayAction {
                    manager.command("seek", target, shouldResume, currentItemId)
                }
            }
        } else seekTo(target)
    }

    fun syncplayPrevious(manager: SyncplayManager?) =
        syncplayEpisode(manager, _uiState.value.previousEpisode)

    fun syncplayNext(manager: SyncplayManager?) =
        syncplayEpisode(manager, _uiState.value.nextEpisode)

    private fun syncplayEpisode(manager: SyncplayManager?, target: MediaItem?) {
        if (target == null) return
        if (manager == null || _uiState.value.local) {
            transitionTo(target)
            return
        }
        if (manager.state.value.active != null) {
            if (!manager.state.value.canControl(session?.userId.orEmpty())) return
            viewModelScope.launch {
                runSyncplayAction { manager.command("media", 0.0, true, target.id) }
            }
        } else transitionTo(target)
    }

    private fun settleSyncplayTimelineIfReady(
        room: SyncplayGroup?,
        engine: EngineState,
        serverNow: Double,
    ) {
        val current = _uiState.value
        if (
            !shouldCompleteSyncplaySettling(
                current.syncplaySettling,
                room,
                current.itemId,
                engine,
                serverNow,
            )
        ) {
            return
        }
        _uiState.value = current.copy(syncplaySettling = false)
        syncplay?.reportPresence(viewing = true, loading = false)
    }

    fun applySyncplayRoom(room: SyncplayGroup, serverNow: Double) {
        if (_uiState.value.local || offlineOnly) return
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
        settleSyncplayTimelineIfReady(room, _uiState.value.engine, serverNow)
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
        backgrounded = true
        backgroundRecoveryGeneration++
        backgroundRecoveryJob?.cancel()
        playbackGeneration++
        playbackLoadJob?.cancel()
        viewerHeartbeatJob?.cancel()
        syncplayTimelineKey = null
        playbackEngine?.pause()
        _uiState.value =
            _uiState.value.copy(
                engine = _uiState.value.engine.copy(isPlaying = false),
                syncplaySettling = false,
            )
    }

    fun recoverFromBackground(): Boolean? {
        if (!backgrounded) return null
        backgrounded = false
        backgroundRecoveryJob?.cancel()
        val recoveryGeneration = ++backgroundRecoveryGeneration
        syncplayTimelineKey = null
        val current = _uiState.value
        val needsPlaybackLoad =
            current.playback == null ||
                current.loading ||
                current.error != null ||
                !current.engine.ready ||
                current.engine.isBuffering
        if (needsPlaybackLoad) {
            loadPlayback(
                PlaybackOptions(startPositionSeconds = currentPlayerPositionSeconds()),
                playWhenReadyOverride = false,
            )
        } else {
            startViewerHeartbeat()
        }
        backgroundRecoveryJob = viewModelScope.launch {
            delay(BACKGROUND_RECOVERY_TIMEOUT_MILLIS)
            if (recoveryGeneration != backgroundRecoveryGeneration) return@launch
            val latest = _uiState.value
            if (latest.error != null) {
                if (latest.syncplaySettling) {
                    _uiState.value = latest.copy(syncplaySettling = false)
                }
                return@launch
            }
            if (
                latest.playback == null ||
                    latest.loading ||
                    !latest.engine.ready ||
                    latest.syncplaySettling
            ) {
                _uiState.value =
                    latest.copy(
                        loading = false,
                        syncplaySettling = false,
                        error = "Playback did not recover after returning to the app",
                    )
                syncplay?.reportPresence(viewing = false, loading = false, immediate = true)
            }
        }
        return needsPlaybackLoad
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

    private suspend fun onPlaybackEnded() {
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
        if (_uiState.value.local) {
            val source = resolvedSource as? ResolvedPlaybackSource.Local
            if (source != null) {
                runCatching {
                        repository.offline
                            ?.playbackState
                            ?.complete(
                                source.profile.id,
                                currentItemId,
                                _uiState.value.engine.durationSeconds,
                                _uiState.value.watchHistoryEnabled,
                            )
                    }
                    .onFailure { error ->
                        Log.w(
                            PLAYBACK_TAG,
                            "local playback completion could not be persisted",
                            error,
                        )
                    }
                val current = _uiState.value.playback
                if (current?.item?.id == currentItemId && _uiState.value.watchHistoryEnabled) {
                    _uiState.value =
                        _uiState.value.copy(
                            playback =
                                current.copy(
                                    item =
                                        current.item.copy(
                                            played = true,
                                            playedPercentage = 0.0,
                                            playbackPositionTicks = 0,
                                        )
                                )
                        )
                }
            }
        }
        advanceAfterEpisodeEnd()
    }

    private fun advanceAfterEpisodeEnd() {
        val nextEpisode = _uiState.value.nextEpisode
        when (
            episodeCompletionAction(
                episodeNeighborsLoaded = _uiState.value.episodeNeighborsLoaded,
                nextEpisode = nextEpisode,
                autoplayNextEpisode = _uiState.value.autoplayNextEpisode,
            )
        ) {
            EpisodeCompletionAction.WAIT_FOR_NEIGHBORS -> {
                pendingCompletionGeneration = playbackGeneration
                return
            }
            EpisodeCompletionAction.CLOSE -> {
                pendingCompletionGeneration = null
                if (_uiState.value.local && _uiState.value.nextEpisodeUnavailable) {
                    Toast.makeText(
                            appContext,
                            R.string.offline_next_episode_not_downloaded,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                }
                requestClose()
                return
            }
            EpisodeCompletionAction.PLAY_NEXT -> Unit
        }
        val target = nextEpisode ?: return
        pendingCompletionGeneration = null
        val manager = syncplay
        if (!_uiState.value.local && manager?.state?.value?.active != null) {
            if (manager.state.value.active?.hostUserId != session?.userId.orEmpty()) return
            viewModelScope.launch {
                runSyncplayAction { manager.command("media", 0.0, true, target.id) }
            }
        } else {
            transitionTo(target)
        }
    }

    private fun transitionTo(target: MediaItem?) {
        if (target == null || transitionInProgress || target.id == currentItemId) return
        transitionInProgress = true
        pendingCompletionGeneration = null
        viewModelScope.launch {
            syncplay?.let { manager ->
                runSyncplayAction { manager.setWatchingTogether(false) }
            }
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
                    nextEpisodeUnavailable = false,
                )
            loadPlayback(playWhenReadyOverride = true)
        }
    }

    fun requestClose() {
        if (transitionInProgress || _uiState.value.closeRequested) return
        transitionInProgress = true
        pendingCompletionGeneration = null
        val outgoing = _uiState.value.playback
        val progressSnapshot = playbackProgressSnapshot()
        invalidateActivePlaybackLoad()
        closeCleanupJob = closeCleanupScope.launch {
            reportProgress(progressSnapshot, requireCurrentGeneration = false)
            cancelPlaybackSession(outgoing)
        }
        _uiState.value = _uiState.value.copy(closeRequested = true)
    }

    internal fun releaseResolvedSource() {
        playbackEngine?.stop()
        resolvedSource?.close()
        resolvedSource = null
    }

    private fun invalidateActivePlaybackLoad() {
        playbackGeneration++
        pendingCompletionGeneration = null
        progressFlushJob?.cancel()
        viewerHeartbeatJob?.cancel()
        playbackAccessJob?.cancel()
        playbackLoadJob?.cancel()
        trickplayJob?.cancel()
        subtitleJob?.cancel()
        episodeNeighborsJob?.cancel()
    }

    private suspend fun cancelPlaybackSession(playback: PlaybackData?) {
        if (playback == null || playback.mode == "local") return
        var stopWorker = playback.mode != "direct"
        playback.viewerSessionId?.let { viewerId ->
            runCatching { repository.endPlaybackViewer(remoteSession, viewerId) }
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
        runCatching { repository.cancelPlaybackSession(remoteSession, sessionId) }
            .onFailure { error ->
                Log.w(
                    PLAYBACK_TAG,
                    "playback session cancellation failed sessionId=$sessionId error=${error.message}",
                )
            }
    }

    fun chooseVideoDecoder(preference: VideoDecoderPreference) {
        (playbackEngine as? Media3DecoderControl)?.selectVideoDecoder(preference)
    }

    fun chooseQuality(value: Int) {
        if (_uiState.value.local) return
        loadPlayback(
            PlaybackOptions(
                maxStreamingBitrate = value.takeIf { it > 0 },
                sourceId = _uiState.value.playback?.source?.id,
                audioStreamId = _uiState.value.selectedAudio,
            )
        )
    }

    fun streamInstead() {
        if (!_uiState.value.canStream) return
        streamRequested = true
        loadPlayback(PlaybackOptions(startPositionSeconds = currentPlayerPositionSeconds()))
    }

    fun tryOtherLocalEngine() {
        val source = resolvedSource as? ResolvedPlaybackSource.Local ?: return
        val position = currentPlayerPositionSeconds()
        val type =
            if (_uiState.value.engineType == PlayerEngine.MEDIA3) PlayerEngine.MPV
            else PlayerEngine.MEDIA3
        createEngine(type)
        _uiState.value =
            _uiState.value.copy(
                engineType = type,
                error = null,
                engineInstance = _uiState.value.engineInstance + 1,
            )
        val data = _uiState.value.playback ?: return
        configurePlaybackSource(data, source)
        playbackEngine?.prepare(source.uri, position, source.data.mimeType, true)
        playbackEngine?.selectAudioTrack(
            data.audioTracks
                .indexOfFirst { it.index == _uiState.value.selectedAudio }
                .coerceAtLeast(0)
        )
    }

    fun chooseAudio(stream: MediaStream) {
        _uiState.value = _uiState.value.copy(selectedAudio = stream.index)
        if (_uiState.value.local) {
            val ordinal =
                _uiState.value.playback?.audioTracks?.indexOfFirst { it.index == stream.index }
                    ?: -1
            if (ordinal >= 0) playbackEngine?.selectAudioTrack(ordinal)
            return
        }
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
        if (streamIndex != null) {
            loadSubtitle(playbackGeneration, requestGeneration)
        }
    }

    private fun loadSubtitle(loadGeneration: Long, requestGeneration: Long) {
        val selected = _uiState.value.selectedSubtitle ?: return
        val playback = _uiState.value.playback ?: return
        val sourceId = playback.source.id
        val source = resolvedSource
        subtitleJob?.cancel()
        subtitleJob = viewModelScope.launch {
            val body =
                runCatching {
                        if (source is ResolvedPlaybackSource.Local) source.subtitleText(selected)
                        else
                            repository.subtitleWebVtt(
                                remoteSession,
                                currentItemId,
                                sourceId,
                                selected,
                            )
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
        val previous = _uiState.value.subtitleStyle
        val next = change(previous)
        _uiState.value = _uiState.value.copy(subtitleStyle = next)
        viewModelScope.launch { repository.saveSubtitleStyle(next) }
    }

    fun toggleTimeDisplayMode() {
        val next = _uiState.value.timeDisplayMode.toggled()
        _uiState.value = _uiState.value.copy(timeDisplayMode = next)
        viewModelScope.launch { repository.savePlaybackTimeDisplayMode(next) }
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
        playbackAccessJob?.cancel()
        progressFlushJob?.let { job ->
            job.invokeOnCompletion { progressReportingScope.cancel() }
        } ?: progressReportingScope.cancel()
        playbackLoadJob?.cancel()
        trickplayJob?.cancel()
        subtitleJob?.cancel()
        episodeNeighborsJob?.cancel()
        backgroundRecoveryJob?.cancel()
        engineJob?.cancel()
        releaseResolvedSource()
        runCatching { playbackEngine?.release() }
        playbackEngine = null
        closeCleanupJob?.let { job ->
            job.invokeOnCompletion { closeCleanupScope.cancel() }
        } ?: closeCleanupScope.cancel()
    }

    private var lastProgressFlushAt = 0L

    private companion object {
        const val PROGRESS_FLUSH_DEBOUNCE_MILLIS = 1_000L
        const val BACKGROUND_RECOVERY_TIMEOUT_MILLIS = 10_000L
    }

    class Factory(
        private val repository: CatalogRepository,
        private val session: AuthSession?,
        private val itemId: String,
        private val context: Context,
        private val initialAudioStreamId: Int? = null,
        private val initialSubtitleStreamIndex: Int? = null,
        private val hasInitialSubtitleSelection: Boolean = false,
        private val syncplay: SyncplayManager? = null,
        private val offlineProfileId: String? = null,
        private val offlineOnly: Boolean = false,
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
                offlineProfileId,
                offlineOnly,
            )
                as T
    }
}

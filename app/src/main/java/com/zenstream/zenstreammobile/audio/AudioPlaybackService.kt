package com.zenstream.zenstreammobile.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.zenstream.zenstreammobile.MainActivity
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.CatalogApi
import com.zenstream.zenstreammobile.data.CatalogException
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.data.audioQueueScope
import com.zenstream.zenstreammobile.data.audioQueueSnapshotFromJson
import com.zenstream.zenstreammobile.data.withPrimaryArtworkFallback
import com.zenstream.zenstreammobile.model.AudioPlayerState
import com.zenstream.zenstreammobile.model.AudioQueueEntry
import com.zenstream.zenstreammobile.model.AudioQueueSnapshot
import com.zenstream.zenstreammobile.model.AudioRepeatMode
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.LibrarySortBy
import com.zenstream.zenstreammobile.model.MediaItem as CatalogMediaItem
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SortOrder
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/**
 * The one audio authority in the app. Video remains owned by PlaybackActivity; this service never
 * joins SyncPlay and never persists a negotiated URL.
 */
@UnstableApi
class AudioPlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var httpFactory: DefaultHttpDataSource.Factory
    private lateinit var dataSourceFactory: DataSource.Factory
    private lateinit var mediaSourceFactory: AudioMediaSourceFactory
    private var librarySession: MediaLibrarySession? = null
    private lateinit var repository: CatalogRepository
    private lateinit var sessionStore: SessionStore
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val progressMutex = Mutex()
    private val queueCommandMutex = Mutex()
    private val persistenceMutex = Mutex()
    private val restoreMutex = Mutex()
    private val autoCatalogMutex = Mutex()
    private var progressJob: Job? = null
    private var positionJob: Job? = null
    private var persistJob: Job? = null
    private var lyricsPrefetchJob: Job? = null
    private var autoAdoptionJob: Job? = null
    private var currentState = AudioPlayerState()
    private var retryEntryId: String? = null
    private val transactionController = AudioPlaybackTransactionController()
    private var loadingCurrent = false
    private var lastAutoplay = false
    private var suppressEnded = false
    private var playerScope: String? = null
    private var activeAudioSource: NormalizedAudioSource? = null
    private var activePlayerGeneration: Long? = null
    private var firstPlayingGeneration: Long? = null
    private var lastKnownPositionEntryId: String? = null
    private var lastKnownPositionMs = 0L
    private var persistenceEpoch = 0L
    private var restoreValidationJob: Job? = null
    private var sessionDetached = false
    private var mediaNotificationController: MediaSession.ControllerInfo? = null
    private var lastNotificationEntryId: String? = null
    private var lastNotificationFavorite: Boolean? = null
    private var lastNotificationPlaying: Boolean? = null
    private val audioDiagnostics = AudioPlaybackDiagnostics { activeAudioSource }

    private fun cancelRecovery() {
        transactionController.cancelRecovery()
    }

    /** Accepts a new source identity and cancels work that belongs to the previous queue state. */
    private fun acceptPlaybackSelection(
        commandSequence: Long,
        entryId: String,
        autoPlay: Boolean,
        positionMs: Long,
    ): PlaybackRequest? {
        val request =
            transactionController.acceptSelection(
                commandSequence = commandSequence,
                entryId = entryId,
                autoPlay = autoPlay,
                positionMs = positionMs,
            )
        if (request != null) {
            if (sessionDetached) attachAudioSession()
            restoreValidationJob?.cancel()
            restoreValidationJob = null
            lyricsPrefetchJob?.cancel()
            lyricsPrefetchJob = null
            autoAdoptionJob?.cancel()
            autoAdoptionJob = null
        }
        return request
    }

    private fun rememberPosition(entryId: String?, positionMs: Long) {
        lastKnownPositionEntryId = entryId
        lastKnownPositionMs = positionMs.coerceAtLeast(0L)
    }

    private fun observedPlayerPositionMs(): Long {
        val entryId = currentState.currentEntry?.entryId
        if (!playerOwnsCurrentQueueEntry()) {
            return if (entryId == lastKnownPositionEntryId) lastKnownPositionMs else 0L
        }
        val playerPositionMs = player.currentPosition.coerceAtLeast(0L)
        if (entryId != lastKnownPositionEntryId) {
            rememberPosition(entryId, playerPositionMs)
        } else if (playerPositionMs > 0L) {
            // Do not replace a useful position with Media3's transient zero while an error,
            // stop(), or a new prepare() is tearing down the failed period.
            lastKnownPositionMs = playerPositionMs
        }
        val transientReset =
            playerPositionMs == 0L &&
                entryId != null &&
                entryId == lastKnownPositionEntryId &&
                (player.playerError != null ||
                    transactionController.activeRecoveryJob?.isActive == true ||
                    loadingCurrent)
        return if (transientReset) lastKnownPositionMs else playerPositionMs
    }

    private fun playerOwnsCurrentQueueEntry(): Boolean {
        val entryId = currentState.currentEntry?.entryId ?: return false
        return player.currentMediaItem?.mediaId == "zenstream:queue:$entryId"
    }

    private fun recoveryPositionMs(entryId: String): Long {
        val lastKnown = lastKnownPositionMs.takeIf { lastKnownPositionEntryId == entryId } ?: 0L
        return selectRecoveryPositionMs(
            playerPositionMs = player.currentPosition,
            lastKnownPositionMs = lastKnown,
            publishedPositionSeconds = currentState.positionSeconds,
        )
    }

    private val catalogLibraries = mutableListOf<Library>()
    private val catalogAlbums = mutableMapOf<String, List<CatalogMediaItem>>()
    private val catalogArtists = mutableMapOf<String, List<CatalogMediaItem>>()
    private val catalogTracks = mutableMapOf<String, List<CatalogMediaItem>>()
    private val catalogArtistAlbums = mutableMapOf<String, List<CatalogMediaItem>>()
    private val catalogArtistTracks = mutableMapOf<String, List<CatalogMediaItem>>()
    private var catalogScope: String? = null
    private var catalogSession: AuthSession? = null

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(applicationContext)
        repository = CatalogRepository(CatalogApi(), sessionStore)
        httpFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false)
        dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        mediaSourceFactory = AudioMediaSourceFactory(dataSourceFactory)
        setMediaNotificationProvider(AudioMediaNotificationProvider())
        player =
            ExoPlayer.Builder(this, preferredAudioRenderersFactory(this))
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
        player.addListener(playerListener)
        player.addAnalyticsListener(audioDiagnostics)
        audioDiagnostics.rendererConfigurationCreated()
        attachAudioSession()
        positionJob = serviceScope.launch {
            while (true) {
                delay(1_000)
                publishPlayerState()
            }
        }
        progressJob = serviceScope.launch {
            while (true) {
                delay(PROGRESS_INTERVAL_MILLIS)
                reportProgress()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession =
        checkNotNull(librarySession)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        intent?.let { command -> serviceScope.launch { dispatchCommand(command) } }
        return START_NOT_STICKY
    }

    /** Dispatches commands without holding the state mutex across network or Media3 waits. */
    private suspend fun dispatchCommand(intent: Intent) {
        try {
            val commandSequence = intent.getLongExtra(AudioServiceBridge.EXTRA_COMMAND_SEQUENCE, 0L)
            when (intent.action) {
                AudioServiceBridge.ACTION_PLAY_QUEUE ->
                    intent.getStringExtra(AudioServiceBridge.EXTRA_SNAPSHOT)?.let {
                        replaceQueue(it, commandSequence)
                    }
                AudioServiceBridge.ACTION_PLAY_QUEUE_ENTRY ->
                    run {
                        intent.getStringExtra(AudioServiceBridge.EXTRA_SNAPSHOT)?.let {
                            seedQueueIfEmpty(it)
                        }
                        playQueueEntry(
                            intent.getStringExtra(AudioServiceBridge.EXTRA_ENTRY_ID),
                            commandSequence,
                        )
                    }
                AudioServiceBridge.ACTION_ADD_QUEUE ->
                    intent.getStringExtra(AudioServiceBridge.EXTRA_SNAPSHOT)?.let {
                        addQueue(it)
                    }
                AudioServiceBridge.ACTION_TOGGLE_PLAYBACK -> togglePlayback()
                AudioServiceBridge.ACTION_NEXT ->
                    advance(force = false, commandSequence = commandSequence)
                AudioServiceBridge.ACTION_PREVIOUS -> previous(commandSequence = commandSequence)
                AudioServiceBridge.ACTION_TOGGLE_SHUFFLE -> toggleShuffle()
                AudioServiceBridge.ACTION_TOGGLE_REPEAT -> toggleRepeat()
                AudioServiceBridge.ACTION_SET_VOLUME -> setVolume()
                AudioServiceBridge.ACTION_TOGGLE_MUTE -> toggleMute()
                AudioServiceBridge.ACTION_RETRY -> retryCurrent(commandSequence)
                AudioServiceBridge.ACTION_SEEK ->
                    seekTo(intent.getLongExtra(AudioServiceBridge.EXTRA_POSITION, 0L))
                AudioServiceBridge.ACTION_REMOVE_QUEUE ->
                    removeQueue(
                        intent.getStringExtra(AudioServiceBridge.EXTRA_ENTRY_ID),
                        commandSequence,
                    )
                AudioServiceBridge.ACTION_REORDER_QUEUE ->
                    reorderQueue(
                        intent.getIntExtra(AudioServiceBridge.EXTRA_FROM, -1),
                        intent.getIntExtra(AudioServiceBridge.EXTRA_TO, -1),
                    )
                AudioServiceBridge.ACTION_CLEAR_QUEUE -> clearQueue(commandSequence)
                AudioServiceBridge.ACTION_PAUSE_FOR_VIDEO ->
                    pauseForVideoAndDetach(
                        intent.getStringExtra(AudioServiceBridge.EXTRA_COMMAND_ID),
                        commandSequence,
                    )
                AudioServiceBridge.ACTION_UPDATE_FAVORITE ->
                    updateFavorite(
                        intent.getStringExtra(AudioServiceBridge.EXTRA_ITEM_ID),
                        intent.getBooleanExtra(AudioServiceBridge.EXTRA_FAVORITE, false),
                    )
                AudioServiceBridge.ACTION_RESTORE -> ensureRestored(commandSequence)
                null -> Unit
                else -> Unit
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            AudioServiceBridge.complete(
                intent.getStringExtra(AudioServiceBridge.EXTRA_COMMAND_ID),
                AudioCommandResult.Failed(error.message ?: "Audio command failed"),
            )
        }
    }

    private suspend fun ensureRestored(commandSequence: Long) = restoreMutex.withLock {
        restoreForCurrentAccount(commandSequence)
    }

    /**
     * Restores the local queue first. Catalog revalidation is deliberately detached from the
     * command path so a cold start cannot delay the first explicit album selection.
     */
    private suspend fun restoreForCurrentAccount(commandSequence: Long) {
        val account = sessionStore.session.first()
        val scope = account?.let(::accountScope)
        val snapshot = account?.let { value ->
            sessionStore.loadAudioQueueSnapshot(value.serverUrl, value.userId)?.takeIf { queued ->
                snapshotBelongsTo(queued, value)
            }
        }

        var restoreGeneration: Long? = null
        val shouldReset = queueCommandMutex.withLock {
            if (!transactionController.acceptsCommand(commandSequence)) {
                false
            } else if (scope == playerScope && account != null && currentState.queue.isNotEmpty()) {
                false
            } else {
                persistenceEpoch += 1L
                persistJob?.cancel()
                restoreValidationJob?.cancel()
                lyricsPrefetchJob?.cancel()
                lyricsPrefetchJob = null
                autoAdoptionJob?.cancel()
                autoAdoptionJob = null
                transactionController.invalidate()
                restoreGeneration = transactionController.currentGeneration()
                suppressEnded = true
                loadingCurrent = false
                activeAudioSource = null
                player.stop()
                player.clearMediaItems()
                retryEntryId = null
                currentState = AudioPlayerState()
                rememberPosition(null, 0L)
                playerScope = scope
                publishAudioState()
                true
            }
        }
        if (!shouldReset || account == null) return

        var restoredGeneration: Long? = null
        if (snapshot != null && snapshot.entries.isNotEmpty()) {
            queueCommandMutex.withLock {
                if (
                    transactionController.currentGeneration() == restoreGeneration &&
                        currentState.queue.isEmpty() &&
                        playerScope == scope
                ) {
                    currentState = snapshot.toPlayerState()
                    rememberPosition(
                        currentState.currentEntry?.entryId,
                        currentState.positionSeconds.coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L,
                    )
                    publishAudioState()
                    restoredGeneration = checkNotNull(restoreGeneration)
                }
            }
        }
        restoreAudioPreferences()
        val shouldRevalidate =
            if (snapshot != null && snapshot.entries.isNotEmpty() && restoredGeneration != null) {
                queueCommandMutex.withLock {
                    transactionController.currentGeneration() == restoredGeneration &&
                        currentState.queue.map { it.entryId } == snapshot.entries.map { it.entryId }
                }
            } else {
                false
            }
        if (shouldRevalidate) {
            scheduleQueueRevalidation(
                account,
                checkNotNull(snapshot),
                checkNotNull(restoredGeneration),
            )
        }
    }

    private fun scheduleQueueRevalidation(
        account: AuthSession,
        snapshot: AudioQueueSnapshot,
        restoreGeneration: Long,
    ) {
        restoreValidationJob?.cancel()
        restoreValidationJob = serviceScope.launch {
            val validated = revalidateQueue(account, snapshot)
            if (validated == null || validated.entries.isEmpty()) {
                val shouldClear = queueCommandMutex.withLock {
                    transactionController.currentGeneration() == restoreGeneration &&
                        currentState.queue.map { it.entryId } == snapshot.entries.map { it.entryId }
                }
                if (shouldClear) clearPersistedSnapshot(account)
                return@launch
            }
            queueCommandMutex.withLock {
                if (
                    transactionController.currentGeneration() != restoreGeneration ||
                        currentState.queue.isEmpty() ||
                        currentState.queue.map { it.entryId } != snapshot.entries.map { it.entryId }
                ) {
                    return@withLock
                }
                currentState =
                    currentState.copy(
                        queue = validated.entries,
                        currentIndex = validated.currentIndex,
                        playedEntryIds = validated.playedEntryIds,
                    )
                publishAudioState()
                persistSnapshot()
            }
        }
    }

    private suspend fun restoreAudioPreferences() {
        val shuffle =
            currentState.queue.takeIf { it.isNotEmpty() }?.let { currentState.shuffle }
                ?: sessionStore.audioShuffle.first()
        val repeatMode =
            currentState.queue.takeIf { it.isNotEmpty() }?.let { currentState.repeatMode }
                ?: sessionStore.audioRepeatMode.first()
        // Android's media stream volume is the only volume authority. Ignore
        // legacy app-local gain/mute values so an old saved mute cannot silence
        // playback after the in-app volume control has been removed.
        queueCommandMutex.withLock {
            player.volume = 1f
            currentState =
                currentState.copy(
                    volume = 1f,
                    muted = false,
                    shuffle = shuffle,
                    repeatMode = repeatMode,
                )
            publishAudioState()
        }
    }

    private suspend fun revalidateQueue(
        account: AuthSession,
        snapshot: AudioQueueSnapshot,
    ): AudioQueueSnapshot? {
        val retained = mutableListOf<AudioQueueEntry>()
        for (entry in snapshot.entries) {
            val refreshed =
                try {
                    repository.catalogItem(account, entry.track.id)
                } catch (error: CatalogException) {
                    when {
                        error.statusCode == 404 -> null
                        error.statusCode == 401 -> {
                            repository.clearSessionIfCurrent(account)
                            entry.track
                        }
                        else -> entry.track
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // A transient outage must not destroy a recoverable local queue.
                    entry.track
                }
            if (refreshed != null) retained += entry.copy(track = refreshed)
        }
        if (retained.isEmpty()) return null
        val currentEntryId = snapshot.entries.getOrNull(snapshot.currentIndex)?.entryId
        val currentIndex =
            retained
                .indexOfFirst { it.entryId == currentEntryId }
                .let { index ->
                    if (index >= 0) index else snapshot.currentIndex.coerceIn(0, retained.lastIndex)
                }
        val retainedIds = retained.mapTo(mutableSetOf()) { it.entryId }
        return snapshot.copy(
            entries = retained,
            currentIndex = currentIndex,
            playedEntryIds = snapshot.playedEntryIds.intersect(retainedIds),
        )
    }

    private suspend fun replaceQueue(encoded: String, commandSequence: Long) {
        val snapshot = parseSnapshot(encoded) ?: return
        val account = sessionStore.session.first() ?: return
        if (!snapshotBelongsTo(snapshot, account)) return
        val entryId = snapshot.entries.getOrNull(snapshot.currentIndex)?.entryId ?: return
        var request: PlaybackRequest? = null
        queueCommandMutex.withLock {
            val accepted =
                acceptPlaybackSelection(
                    commandSequence = commandSequence,
                    entryId = entryId,
                    autoPlay = true,
                    positionMs = 0L,
                )
            if (accepted == null) return@withLock
            request = accepted
            sessionDetached = false
            playerScope = accountScope(account)
            loadingCurrent = true
            // PLAY_QUEUE is an explicit user selection, not queue restoration. Never carry a
            // position from the previously playing item (or from a stale caller snapshot) into
            // the newly selected track.
            currentState =
                snapshot
                    .toPlayerState()
                    .copy(
                        positionSeconds = 0L,
                        positionMillis = 0L,
                        positionUpdatedAtElapsedRealtime = SystemClock.elapsedRealtime(),
                        durationSeconds = 0L,
                        isPlaying = false,
                        isLoading = true,
                        error = null,
                    )
            rememberPosition(currentState.currentEntry?.entryId, 0L)
            suppressEnded = true
            activeAudioSource = null
            player.stop()
            player.clearMediaItems()
            retryEntryId = null
            lastAutoplay = true
            Log.i(
                AUDIO_TAG,
                "audio selection accepted generation=${accepted.generation} sequence=$commandSequence entry=$entryId",
            )
            publishAudioState()
        }
        val accepted = request ?: return
        sessionStore.saveAudioShuffle(snapshot.shuffle)
        sessionStore.saveAudioRepeatMode(snapshot.repeatMode)
        persistSnapshot()
        launchLoad(accepted)
    }

    private suspend fun addQueue(encoded: String) {
        val snapshot = parseSnapshot(encoded) ?: return
        val additions = snapshot.entries
        if (additions.isEmpty()) return
        val account = sessionStore.session.first() ?: return
        if (!snapshotBelongsTo(snapshot, account)) return
        queueCommandMutex.withLock {
            val wasEmpty = currentState.queue.isEmpty()
            val merged = (currentState.queue + additions).distinctBy { it.entryId }
            currentState =
                currentState.copy(
                    queue = merged,
                    currentIndex =
                        if (wasEmpty) 0
                        else currentState.currentIndex.coerceIn(0, merged.lastIndex),
                    playedEntryIds =
                        currentState.playedEntryIds.intersect(merged.map { it.entryId }.toSet()),
                    error = null,
                )
            publishPlayerState()
        }
        persistSnapshot()
    }

    private suspend fun seedQueueIfEmpty(encoded: String) {
        val snapshot = parseSnapshot(encoded) ?: return
        val account = sessionStore.session.first() ?: return
        if (!snapshotBelongsTo(snapshot, account) || snapshot.entries.isEmpty()) return
        queueCommandMutex.withLock {
            if (currentState.queue.isNotEmpty()) return@withLock
            playerScope = accountScope(account)
            currentState = snapshot.toPlayerState()
            rememberPosition(
                currentState.currentEntry?.entryId,
                currentState.positionSeconds.coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L,
            )
            publishAudioState()
        }
    }

    private suspend fun togglePlayback() {
        var progress: ProgressSample? = null
        var request: PlaybackRequest? = null
        var shouldPersist = false
        queueCommandMutex.withLock {
            if (currentState.queue.isEmpty() || loadingCurrent) return@withLock
            if (player.isPlaying) {
                lastAutoplay = false
                progress = captureProgressSample(paused = true)
                player.pause()
                publishPlayerState()
                shouldPersist = true
            } else {
                lastAutoplay = true
                val entry = currentState.currentEntry ?: return@withLock
                if (
                    player.currentMediaItem == null ||
                        player.playerError != null ||
                        player.playbackState == Player.STATE_ENDED
                ) {
                    val position =
                        if (player.playbackState == Player.STATE_ENDED) 0L
                        else recoveryPositionMs(entry.entryId)
                    request =
                        acceptPlaybackSelection(
                            commandSequence = 0L,
                            entryId = entry.entryId,
                            autoPlay = true,
                            positionMs = position,
                        )
                    if (request == null) return@withLock
                    loadingCurrent = true
                    activeAudioSource = null
                    suppressEnded = true
                    player.stop()
                    player.clearMediaItems()
                    currentState =
                        currentState.copy(isLoading = true, error = null, isPlaying = false)
                    publishAudioState()
                } else {
                    player.play()
                    publishPlayerState()
                }
            }
        }
        progress?.let(::enqueueProgress)
        if (shouldPersist) persistSnapshot()
        request?.let(::launchLoad)
    }

    private suspend fun retryCurrent(commandSequence: Long) {
        var request: PlaybackRequest? = null
        queueCommandMutex.withLock {
            val entry = currentState.currentEntry ?: return@withLock
            val resumePositionMs = recoveryPositionMs(entry.entryId)
            request =
                acceptPlaybackSelection(
                    commandSequence = commandSequence,
                    entryId = entry.entryId,
                    autoPlay = true,
                    positionMs = resumePositionMs,
                )
            if (request == null) return@withLock
            retryEntryId = null
            suppressEnded = true
            loadingCurrent = true
            activeAudioSource = null
            player.stop()
            player.clearMediaItems()
            currentState = currentState.copy(isLoading = true, error = null, isPlaying = false)
            publishAudioState()
        }
        request?.let(::launchLoad)
    }

    private fun launchLoad(request: PlaybackRequest) {
        val job =
            serviceScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                val runningJob = kotlinx.coroutines.currentCoroutineContext()[Job]
                try {
                    loadCurrent(request)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    Log.i(
                        AUDIO_TAG,
                        "audio load cancelled generation=${request.generation} entry=${request.entryId}",
                    )
                    throw error
                } finally {
                    runningJob?.let(transactionController::clearLoad)
                }
            }
        transactionController.registerLoad(job)
        job.start()
    }

    private suspend fun loadCurrent(request: PlaybackRequest) {
        val entry =
            queueCommandMutex.withLock {
                currentState.currentEntry?.takeIf {
                    it.entryId == request.entryId &&
                        transactionController.isCurrent(request, it.entryId)
                }
            } ?: return
        val account = sessionStore.session.first()
        if (account == null) {
            queueCommandMutex.withLock {
                if (transactionController.isCurrent(request, currentState.currentEntry?.entryId)) {
                    loadingCurrent = false
                    currentState =
                        currentState.copy(
                            isLoading = false,
                            error = "Sign in to play music",
                        )
                    publishAudioState()
                }
            }
            return
        }
        httpFactory.setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${account.token}"))
        queueCommandMutex.withLock {
            if (!transactionController.isCurrent(request, currentState.currentEntry?.entryId)) {
                return@withLock
            }
            loadingCurrent = true
            lastAutoplay = request.autoPlay
            currentState =
                currentState.copy(
                    isLoading = true,
                    error = null,
                    positionUpdatedAtElapsedRealtime = 0L,
                )
            publishAudioState()
        }
        if (!transactionController.isCurrent(request, currentState.currentEntry?.entryId)) return

        try {
            Log.i(
                AUDIO_TAG,
                "audio negotiation start generation=${request.generation} entry=${request.entryId}",
            )
            val requestedPositionMs = request.positionMs.coerceAtLeast(0L)
            val playbackOptions =
                PlaybackOptions(
                    engine = PlayerEngine.MEDIA3,
                    startPositionSeconds = requestedPositionMs / 1_000.0,
                )
            val data = repository.playback(account, entry.track.id, playbackOptions)
            Log.i(
                AUDIO_TAG,
                "audio negotiation end generation=${request.generation} entry=${request.entryId}",
            )
            if (!transactionController.isCurrent(request, currentState.currentEntry?.entryId))
                return
            val candidateUrl =
                data.url ?: data.source.url ?: error("Server did not return an audio URL")
            val url = resolveSameOriginUrl(account.serverUrl, candidateUrl)
            val durationSeconds =
                data.durationSeconds ?: data.source.durationSeconds ?: entry.track.durationSeconds
            val audioStream =
                (data.audioTracks + data.source.mediaStreams).firstOrNull {
                    it.type.equals("audio", ignoreCase = true)
                }
            val sourceFormat =
                (data.source.container ?: data.mimeType)
                    ?.substringBefore(',')
                    ?.substringBefore(';')
                    ?.trim()
                    ?.takeIf(String::isNotBlank)
            val normalizedSource =
                normalizeAudioSource(
                    url = url,
                    mimeType = data.mimeType,
                    mode = data.mode,
                    sessionId = data.sessionId,
                    durationSeconds = durationSeconds,
                    expiresAt = data.expiresAt,
                )
            val mediaItem =
                MediaItem.Builder()
                    .setMediaId("zenstream:queue:${entry.entryId}")
                    .setUri(Uri.parse(url))
                    .apply { normalizedSource.mimeType?.let(::setMimeType) }
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(entry.track.name)
                            .setArtist(primaryArtist(entry.track))
                            .setAlbumTitle(entry.track.album)
                            .setDisplayTitle(entry.track.name)
                            .apply {
                                authenticatedArtworkUri(entry.track, account)?.let {
                                    setArtworkUri(it)
                                }
                            }
                            .build()
                    )
                    .build()

            var mediaApplied = false
            queueCommandMutex.withLock {
                if (!transactionController.isCurrent(request, currentState.currentEntry?.entryId)) {
                    return@withLock
                }
                activeAudioSource = normalizedSource
                activePlayerGeneration = request.generation
                audioDiagnostics.sourceSelected(normalizedSource)
                player.volume = 1f
                player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
                suppressEnded = false
                firstPlayingGeneration = null
                Log.i(
                    AUDIO_TAG,
                    "audio media prepare generation=${request.generation} entry=${request.entryId}",
                )
                // Explicitly apply the requested start for every prepare, including zero. This
                // prevents a reused ExoPlayer instance from retaining the previous period's
                // position.
                player.prepare()
                // Media3 can report a synchronous prepare error. That callback may invalidate
                // this generation before prepare() returns, so do not let the stale request
                // finish mutating state or starting playback.
                if (!transactionController.isCurrent(request, currentState.currentEntry?.entryId)) {
                    return@withLock
                }
                player.seekTo(requestedPositionMs)
                rememberPosition(entry.entryId, requestedPositionMs)
                loadingCurrent = player.playbackState != Player.STATE_READY
                currentState = markQueueEntryPlayed(currentState, entry.entryId) ?: currentState
                currentState =
                    currentState.copy(
                        positionSeconds = requestedPositionMs / 1_000L,
                        positionMillis = requestedPositionMs,
                        positionUpdatedAtElapsedRealtime = SystemClock.elapsedRealtime(),
                        // Readiness is authoritative. The listener clears this only at READY.
                        isLoading = loadingCurrent,
                        error = null,
                        durationSeconds = durationSeconds?.toLong()?.coerceAtLeast(0L) ?: 0L,
                        sourceFormat = sourceFormat,
                        sourceBitrate = audioStream?.bitrate ?: data.source.bitrate,
                        sourceSampleRate = audioStream?.sampleRate,
                        playbackMode = data.mode,
                    )
                if (request.autoPlay) player.play()
                if (!transactionController.isCurrent(request, currentState.currentEntry?.entryId)) {
                    return@withLock
                }
                mediaApplied = true
                Log.i(
                    AUDIO_TAG,
                    "audio player play generation=${request.generation} entry=${request.entryId} loading=$loadingCurrent",
                )
                publishAudioState()
            }
            if (
                !mediaApplied ||
                    !transactionController.isCurrent(request, currentState.currentEntry?.entryId)
            ) {
                return
            }
            persistSnapshot()
            prefetchLyrics(account, entry)
            // Telemetry is deliberately after player.play(). It cannot delay or fail playback.
            if (request.autoPlay) launchPlayStart(account, entry, request.generation)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (!transactionController.isCurrent(request, currentState.currentEntry?.entryId))
                return
            if ((error as? CatalogException)?.statusCode == 401)
                repository.clearSessionIfCurrent(account)
            queueCommandMutex.withLock {
                if (!transactionController.isCurrent(request, currentState.currentEntry?.entryId)) {
                    return@withLock
                }
                loadingCurrent = false
                activeAudioSource = null
                activePlayerGeneration = null
                currentState =
                    currentState.copy(
                        isLoading = false,
                        error = error.message ?: "Audio could not be played",
                    )
                player.pause()
                player.clearMediaItems()
                publishAudioState()
            }
        }
    }

    private fun prefetchLyrics(account: AuthSession, entry: AudioQueueEntry) {
        lyricsPrefetchJob?.cancel()
        val nextEntry = currentState.queue.getOrNull(currentState.currentIndex + 1)
        val candidates = listOf(entry, nextEntry).filterNotNull().distinctBy { it.track.id }
        lyricsPrefetchJob = serviceScope.launch {
            for (candidate in candidates) {
                repository.prefetchAudioLyrics(account, candidate.track.id)
            }
        }
    }

    private fun launchPlayStart(
        account: AuthSession,
        entry: AudioQueueEntry,
        expectedGeneration: Long? = null,
    ) {
        serviceScope.launch {
            recordPlayStart(account, entry, expectedGeneration)
        }
    }

    private suspend fun recordPlayStart(
        account: AuthSession,
        oldEntry: AudioQueueEntry,
        expectedGeneration: Long? = null,
    ) {
        val entry =
            queueCommandMutex.withLock {
                currentState.currentEntry?.takeIf {
                    it.entryId == oldEntry.entryId &&
                        (expectedGeneration == null ||
                            transactionController.currentGeneration() == expectedGeneration)
                }
            } ?: return
        // Reuse a persisted idempotency key after a retry or process recreation.
        // The server treats repeated requests with that key as one play-start.
        val instanceId = entry.playbackInstanceId ?: UUID.randomUUID().toString()
        if (entry.playbackInstanceId == null) {
            var assigned = false
            queueCommandMutex.withLock {
                if (
                    currentState.currentEntry?.entryId != entry.entryId ||
                        (expectedGeneration != null &&
                            transactionController.currentGeneration() != expectedGeneration)
                ) {
                    return@withLock
                }
                currentState =
                    currentState.copy(
                        queue =
                            currentState.queue.map { value ->
                                if (value.entryId == entry.entryId)
                                    value.copy(playbackInstanceId = instanceId)
                                else value
                            }
                    )
                assigned = true
            }
            if (assigned) persistSnapshot()
        }
        repeat(PLAY_START_ATTEMPTS) { attempt ->
            try {
                repository.recordAudioPlayStart(account, entry.track.id, instanceId)
                return
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: CatalogException) {
                if (error.statusCode == 401) {
                    repository.clearSessionIfCurrent(account)
                    return
                }
                if (attempt == PLAY_START_ATTEMPTS - 1) {
                    Log.w(AUDIO_TAG, "Audio play-start telemetry failed", error)
                    return
                }
            } catch (error: Throwable) {
                if (attempt == PLAY_START_ATTEMPTS - 1) {
                    Log.w(AUDIO_TAG, "Audio play-start telemetry failed", error)
                    return
                }
            }
            delay(PLAY_START_RETRY_DELAY_MILLIS * (attempt + 1))
        }
    }

    private suspend fun playQueueEntry(entryId: String?, commandSequence: Long) {
        if (entryId.isNullOrBlank()) return
        var request: PlaybackRequest? = null
        var progress: ProgressSample? = null
        var shouldPersist = false
        queueCommandMutex.withLock {
            val targetIndex = queueIndexForEntry(currentState.queue, entryId) ?: return@withLock
            val target = currentState.queue[targetIndex]
            if (target.entryId == currentState.currentEntry?.entryId) {
                if (loadingCurrent) return@withLock
                lastAutoplay = true
                if (
                    player.currentMediaItem == null ||
                        player.playerError != null ||
                        player.playbackState == Player.STATE_ENDED
                ) {
                    val position =
                        if (player.playbackState == Player.STATE_ENDED) 0L
                        else recoveryPositionMs(target.entryId)
                    request =
                        acceptPlaybackSelection(
                            commandSequence,
                            target.entryId,
                            autoPlay = true,
                            positionMs = position,
                        )
                    if (request == null) return@withLock
                    loadingCurrent = true
                    activeAudioSource = null
                    suppressEnded = true
                    player.stop()
                    player.clearMediaItems()
                    currentState =
                        currentState.copy(isLoading = true, isPlaying = false, error = null)
                    publishAudioState()
                } else {
                    player.play()
                    publishPlayerState()
                }
                return@withLock
            }

            request =
                acceptPlaybackSelection(
                    commandSequence,
                    target.entryId,
                    autoPlay = true,
                    positionMs = 0L,
                )
            if (request == null) return@withLock
            progress = captureProgressSample(paused = true)
            currentState =
                selectQueueEntryForPlayback(currentState, target.entryId) ?: return@withLock
            rememberPosition(target.entryId, 0L)
            currentState =
                currentState.copy(
                    positionSeconds = 0L,
                    positionMillis = 0L,
                    positionUpdatedAtElapsedRealtime = SystemClock.elapsedRealtime(),
                    durationSeconds = 0L,
                    isPlaying = false,
                    isLoading = true,
                    error = null,
                    sourceFormat = null,
                    sourceBitrate = null,
                    sourceSampleRate = null,
                    playbackMode = null,
                )
            loadingCurrent = true
            activeAudioSource = null
            suppressEnded = true
            player.stop()
            player.clearMediaItems()
            retryEntryId = null
            lastAutoplay = true
            publishAudioState()
            shouldPersist = true
        }
        progress?.let(::enqueueProgress)
        if (shouldPersist) persistSnapshot()
        request?.let(::launchLoad)
    }

    private suspend fun advance(force: Boolean, commandSequence: Long) {
        var request: PlaybackRequest? = null
        var progress: ProgressSample? = null
        var shouldPersist = false
        queueCommandMutex.withLock {
            if (currentState.queue.isEmpty()) return@withLock
            val selection = nextQueueSelection(currentState, force)
            if (selection == null) {
                if (!transactionController.invalidate(commandSequence)) return@withLock
                progress = captureProgressSample(paused = true)
                lastAutoplay = false
                loadingCurrent = false
                player.pause()
                player.seekTo(0L)
                rememberPosition(currentState.currentEntry?.entryId, 0L)
                currentState =
                    currentState.copy(isPlaying = false, isLoading = false, positionSeconds = 0L)
                publishPlayerState()
                shouldPersist = true
                return@withLock
            }
            val target = currentState.queue[selection.index]
            request =
                acceptPlaybackSelection(
                    commandSequence,
                    target.entryId,
                    autoPlay = true,
                    positionMs = 0L,
                )
            if (request == null) return@withLock
            progress = captureProgressSample(paused = true)
            currentState =
                currentState.copy(
                    currentIndex = selection.index,
                    playedEntryIds = selection.playedEntryIds,
                    positionSeconds = 0L,
                    positionMillis = 0L,
                    positionUpdatedAtElapsedRealtime = SystemClock.elapsedRealtime(),
                    durationSeconds = 0L,
                    isPlaying = false,
                    isLoading = true,
                    error = null,
                    sourceFormat = null,
                    sourceBitrate = null,
                    sourceSampleRate = null,
                    playbackMode = null,
                )
            rememberPosition(target.entryId, 0L)
            loadingCurrent = true
            activeAudioSource = null
            suppressEnded = true
            player.stop()
            player.clearMediaItems()
            lastAutoplay = true
            retryEntryId = null
            publishAudioState()
            shouldPersist = true
        }
        progress?.let(::enqueueProgress)
        if (shouldPersist) persistSnapshot()
        request?.let(::launchLoad)
    }

    private suspend fun previous(commandSequence: Long) {
        var request: PlaybackRequest? = null
        var progress: ProgressSample? = null
        var shouldPersist = false
        queueCommandMutex.withLock {
            if (currentState.queue.isEmpty()) return@withLock
            if (player.currentPosition > 5_000L) {
                player.seekTo(0L)
                rememberPosition(currentState.currentEntry?.entryId, 0L)
                return@withLock
            }
            val previous = previousQueueIndex(currentState.queue.size, currentState.currentIndex)
            if (previous == null) {
                player.seekTo(0L)
                rememberPosition(currentState.currentEntry?.entryId, 0L)
                return@withLock
            }
            val target = currentState.queue[previous]
            request =
                acceptPlaybackSelection(
                    commandSequence,
                    target.entryId,
                    autoPlay = true,
                    positionMs = 0L,
                )
            if (request == null) return@withLock
            progress = captureProgressSample(paused = true)
            currentState =
                currentState.copy(
                    currentIndex = previous,
                    positionSeconds = 0L,
                    positionMillis = 0L,
                    positionUpdatedAtElapsedRealtime = SystemClock.elapsedRealtime(),
                    durationSeconds = 0L,
                    isPlaying = false,
                    isLoading = true,
                    error = null,
                    sourceFormat = null,
                    sourceBitrate = null,
                    sourceSampleRate = null,
                    playbackMode = null,
                )
            rememberPosition(target.entryId, 0L)
            loadingCurrent = true
            activeAudioSource = null
            suppressEnded = true
            player.stop()
            player.clearMediaItems()
            retryEntryId = null
            lastAutoplay = true
            publishAudioState()
            shouldPersist = true
        }
        progress?.let(::enqueueProgress)
        if (shouldPersist) persistSnapshot()
        request?.let(::launchLoad)
    }

    private suspend fun toggleShuffle() {
        val value: Boolean
        queueCommandMutex.withLock {
            value = !currentState.shuffle
            currentState =
                if (value) shuffleQueueKeepingCurrent(currentState)
                else currentState.copy(shuffle = false)
            publishPlayerState()
        }
        sessionStore.saveAudioShuffle(value)
        persistSnapshot()
    }

    private suspend fun toggleRepeat() {
        val value: AudioRepeatMode
        queueCommandMutex.withLock {
            value = currentState.repeatMode.next()
            currentState = currentState.copy(repeatMode = value)
            publishPlayerState()
        }
        sessionStore.saveAudioRepeatMode(value)
        persistSnapshot()
    }

    private suspend fun setVolume() {
        // Retain the bridge action for compatibility with older clients, but
        // never apply software gain. Volume belongs to Android's media stream.
        queueCommandMutex.withLock {
            player.volume = 1f
            currentState = currentState.copy(volume = 1f, muted = false)
            publishPlayerState()
        }
    }

    private suspend fun toggleMute() {
        // There is no app-level mute anymore; use the phone's volume controls.
        queueCommandMutex.withLock {
            player.volume = 1f
            currentState = currentState.copy(volume = 1f, muted = false)
            publishPlayerState()
        }
    }

    private suspend fun seekTo(positionSeconds: Long) {
        val position = positionSeconds.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE / 1_000L)
        queueCommandMutex.withLock {
            if (loadingCurrent || currentState.currentEntry == null) return@withLock
            player.seekTo(position * 1_000L)
            rememberPosition(currentState.currentEntry?.entryId, position * 1_000L)
            currentState = currentState.copy(positionSeconds = position)
            publishPlayerState()
        }
        persistSnapshot()
    }

    private suspend fun removeQueue(entryId: String?, commandSequence: Long) {
        if (entryId.isNullOrBlank()) return
        var request: PlaybackRequest? = null
        var progress: ProgressSample? = null
        var shouldClear = false
        var shouldPersist = false
        queueCommandMutex.withLock {
            if (currentState.queue.none { it.entryId == entryId }) return@withLock
            val removal = removeQueueEntry(currentState, entryId) ?: return@withLock
            if (removal.entries.isEmpty()) {
                shouldClear = true
                return@withLock
            }
            val wasCurrent = removal.removedCurrent
            val wasPlaying = player.isPlaying || lastAutoplay
            if (wasCurrent) progress = captureProgressSample(paused = true)
            currentState =
                currentState.copy(
                    queue = removal.entries,
                    currentIndex = removal.currentIndex,
                    playedEntryIds =
                        currentState.playedEntryIds.intersect(
                            removal.entries.map { it.entryId }.toSet()
                        ),
                    positionSeconds = if (wasCurrent) 0L else currentState.positionSeconds,
                    durationSeconds = if (wasCurrent) 0L else currentState.durationSeconds,
                    sourceFormat = if (wasCurrent) null else currentState.sourceFormat,
                    sourceBitrate = if (wasCurrent) null else currentState.sourceBitrate,
                    sourceSampleRate = if (wasCurrent) null else currentState.sourceSampleRate,
                    playbackMode = if (wasCurrent) null else currentState.playbackMode,
                    isPlaying = if (wasCurrent) false else currentState.isPlaying,
                    isLoading = if (wasCurrent) true else currentState.isLoading,
                )
            if (wasCurrent) {
                val target = currentState.currentEntry ?: return@withLock
                request =
                    acceptPlaybackSelection(
                        commandSequence = 0L,
                        entryId = target.entryId,
                        autoPlay = wasPlaying,
                        positionMs = 0L,
                    )
                if (request == null) return@withLock
                loadingCurrent = true
                activeAudioSource = null
                rememberPosition(target.entryId, 0L)
                suppressEnded = true
                player.stop()
                player.clearMediaItems()
                retryEntryId = null
                lastAutoplay = wasPlaying
            }
            publishPlayerState()
            shouldPersist = true
        }
        if (shouldClear) {
            clearQueue(commandSequence)
            return
        }
        progress?.let(::enqueueProgress)
        if (shouldPersist) persistSnapshot()
        request?.let(::launchLoad)
    }

    private suspend fun reorderQueue(from: Int, to: Int) {
        queueCommandMutex.withLock {
            currentState = reorderQueue(currentState, from, to) ?: return@withLock
            publishPlayerState()
        }
        persistSnapshot()
    }

    private suspend fun clearQueue(commandSequence: Long) {
        Log.i(AUDIO_TAG, "audio stop requested sequence=$commandSequence")
        val account = sessionStore.session.first()
        var progress: ProgressSample? = null
        var applied = false
        var teardownGeneration = 0L
        queueCommandMutex.withLock {
            if (!transactionController.invalidate(commandSequence)) return@withLock
            applied = true
            teardownGeneration = transactionController.currentGeneration()
            persistenceEpoch += 1L
            persistJob?.cancel()
            restoreValidationJob?.cancel()
            progress = captureProgressSample(paused = true)
            suppressEnded = true
            loadingCurrent = false
            activeAudioSource = null
            lastAutoplay = false
            player.stop()
            player.clearMediaItems()
            retryEntryId = null
            rememberPosition(null, 0L)
            currentState = AudioPlayerState()
            publishAudioState()
        }
        if (!applied) return
        Log.i(AUDIO_TAG, "audio stop applied queue=empty")
        // This is local persistence only; it is not on the progress/network critical path.
        if (account != null) clearPersistedSnapshot(account)
        progress?.let(::enqueueProgress)
        lyricsPrefetchJob?.cancel()
        lyricsPrefetchJob = null
        autoAdoptionJob?.cancel()
        autoAdoptionJob = null
        if (!detachAudioSessionIfCurrent(commandSequence, teardownGeneration)) return
    }

    private suspend fun pauseForVideoAndDetach(commandId: String?, commandSequence: Long) {
        Log.i(AUDIO_TAG, "audio video handoff requested sequence=$commandSequence")
        val account = sessionStore.session.first()
        var progress: ProgressSample? = null
        var applied = false
        var teardownGeneration = 0L
        queueCommandMutex.withLock {
            if (!transactionController.invalidate(commandSequence)) return@withLock
            applied = true
            teardownGeneration = transactionController.currentGeneration()
            persistenceEpoch += 1L
            persistJob?.cancel()
            progress = captureProgressSample(paused = true)
            progress?.let { sample ->
                val positionMs = (sample.positionSeconds * 1_000.0).toLong().coerceAtLeast(0L)
                currentState =
                    currentState.copy(
                        positionSeconds = positionMs / 1_000L,
                        positionMillis = positionMs,
                        positionUpdatedAtElapsedRealtime = SystemClock.elapsedRealtime(),
                    )
            }
            suppressEnded = true
            loadingCurrent = false
            activeAudioSource = null
            lastAutoplay = false
            lyricsPrefetchJob?.cancel()
            lyricsPrefetchJob = null
            autoAdoptionJob?.cancel()
            autoAdoptionJob = null
            player.pause()
            player.stop()
            player.clearMediaItems()
            currentState = currentState.copy(isPlaying = false, isLoading = false)
            publishAudioState()
        }
        if (!applied) {
            AudioServiceBridge.complete(
                commandId,
                AudioCommandResult.Failed("A newer audio command is already active"),
            )
            return
        }
        if (account != null) persistSnapshotNow(account)
        progress?.let(::enqueueProgress)
        if (!detachAudioSessionIfCurrent(commandSequence, teardownGeneration)) {
            AudioServiceBridge.complete(
                commandId,
                AudioCommandResult.Failed("A newer audio command is already active"),
            )
            return
        }
        AudioServiceBridge.complete(commandId, AudioCommandResult.Applied)
        Log.i(AUDIO_TAG, "audio video handoff acknowledged")
    }

    private suspend fun detachAudioSessionIfCurrent(
        commandSequence: Long,
        teardownGeneration: Long,
    ): Boolean = queueCommandMutex.withLock {
        if (!transactionController.canFinalizeTeardown(commandSequence, teardownGeneration)) {
            Log.i(
                AUDIO_TAG,
                "audio session teardown skipped sequence=$commandSequence generation=$teardownGeneration",
            )
            return@withLock false
        }
        detachAudioSessionAndStopService()
        stopSelf()
        true
    }

    private fun detachAudioSessionAndStopService() {
        if (sessionDetached) return
        sessionDetached = true
        mediaNotificationController = null
        lastNotificationEntryId = null
        lastNotificationFavorite = null
        lastNotificationPlaying = null
        librarySession?.release()
        librarySession = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        Log.i(AUDIO_TAG, "audio session detached foreground removed")
    }

    private fun notificationButtonPreferences(
        showPauseButton: Boolean = player.isPlaying
    ): ImmutableList<CommandButton> {
        val heart = AudioNotificationControl.Heart
        val previous = AudioNotificationControl.Previous
        val playPause = AudioNotificationControl.PlayPause
        val next = AudioNotificationControl.Next
        val stop = AudioNotificationControl.Stop
        val favorite = currentState.currentEntry?.track?.favorite == true

        fun customCommand(action: String): SessionCommand = SessionCommand(action, Bundle.EMPTY)

        fun compactExtras(control: AudioNotificationControl): Bundle =
            Bundle().apply {
                AudioNotificationControls.compactIndexFor(control)?.let { index ->
                    putInt(DefaultMediaNotificationProvider.COMMAND_KEY_COMPACT_VIEW_INDEX, index)
                }
            }

        val buttons =
            mapOf(
                heart to
                    CommandButton.Builder(AudioNotificationControls.heartIcon(favorite))
                        .setSessionCommand(
                            customCommand(AudioNotificationControls.ACTION_TOGGLE_FAVORITE)
                        )
                        .setDisplayName(
                            getString(
                                if (favorite) R.string.remove_favorite else R.string.add_favorite
                            )
                        )
                        .setSlots(AudioNotificationControls.slotFor(heart))
                        .setExtras(compactExtras(heart))
                        .build(),
                previous to
                    CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                        .setSessionCommand(customCommand(AudioNotificationControls.ACTION_PREVIOUS))
                        .setDisplayName(getString(R.string.audio_previous_track))
                        .setSlots(AudioNotificationControls.slotFor(previous))
                        .setExtras(compactExtras(previous))
                        .build(),
                playPause to
                    CommandButton.Builder(
                            if (showPauseButton) CommandButton.ICON_PAUSE
                            else CommandButton.ICON_PLAY
                        )
                        .setPlayerCommand(Player.COMMAND_PLAY_PAUSE)
                        .setDisplayName(
                            getString(if (showPauseButton) R.string.pause else R.string.play)
                        )
                        .setSlots(AudioNotificationControls.slotFor(playPause))
                        .setExtras(compactExtras(playPause))
                        .build(),
                next to
                    CommandButton.Builder(CommandButton.ICON_NEXT)
                        .setSessionCommand(customCommand(AudioNotificationControls.ACTION_NEXT))
                        .setDisplayName(getString(R.string.audio_next_track))
                        .setSlots(AudioNotificationControls.slotFor(next))
                        .setExtras(compactExtras(next))
                        .build(),
                stop to
                    CommandButton.Builder(CommandButton.ICON_STOP)
                        .setSessionCommand(customCommand(AudioNotificationControls.ACTION_STOP))
                        .setDisplayName(getString(R.string.stop_playing))
                        .setSlots(AudioNotificationControls.slotFor(stop))
                        .setExtras(compactExtras(stop))
                        .build(),
            )

        return ImmutableList.copyOf(
            AudioNotificationControls.expandedOrder.map { control ->
                checkNotNull(buttons[control])
            }
        )
    }

    private fun refreshNotificationControls(force: Boolean = false) {
        val session = librarySession ?: return
        val entryId = currentState.currentEntry?.entryId
        val favorite = currentState.currentEntry?.track?.favorite == true
        val isPlaying = player.isPlaying
        if (
            !force &&
                entryId == lastNotificationEntryId &&
                favorite == lastNotificationFavorite &&
                isPlaying == lastNotificationPlaying
        ) {
            return
        }
        lastNotificationEntryId = entryId
        lastNotificationFavorite = favorite
        lastNotificationPlaying = isPlaying
        mediaNotificationController?.let { controller ->
            session.setMediaButtonPreferences(controller, notificationButtonPreferences(isPlaying))
        }
    }

    private inner class AudioMediaNotificationProvider :
        DefaultMediaNotificationProvider(
            this@AudioPlaybackService,
            object : DefaultMediaNotificationProvider.NotificationIdProvider {
                override fun getNotificationId(mediaSession: MediaSession): Int =
                    AudioNotificationConfig.NOTIFICATION_ID
            },
            AudioNotificationConfig.CHANNEL_ID,
            R.string.app_name,
        ) {
        init {
            setSmallIcon(R.drawable.ic_launcher_foreground)
        }

        @Suppress("UNUSED_PARAMETER")
        override fun getMediaButtons(
            session: MediaSession,
            playerCommands: Player.Commands,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            showPauseButton: Boolean,
        ): ImmutableList<CommandButton> = notificationButtonPreferences(showPauseButton)
    }

    private fun attachAudioSession() {
        if (librarySession != null && !sessionDetached) return
        startServiceForeground()
        val sessionActivity =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val session =
            MediaLibrarySession.Builder(this, player, LibraryCallback())
                .setSessionActivity(sessionActivity)
                .setMediaButtonPreferences(notificationButtonPreferences())
                .build()
        librarySession = session
        sessionDetached = false
        addSession(session)
        refreshNotificationControls(force = true)
    }

    private suspend fun updateFavorite(itemId: String?, favorite: Boolean) {
        if (itemId.isNullOrBlank()) return
        var changed = false
        queueCommandMutex.withLock {
            val updated =
                currentState.queue.map { entry ->
                    if (entry.track.id == itemId)
                        entry.copy(track = entry.track.copy(favorite = favorite))
                    else entry
                }
            if (updated == currentState.queue) return@withLock
            currentState = currentState.copy(queue = updated)
            publishPlayerState()
            changed = true
        }
        if (changed) persistSnapshot()
    }

    private suspend fun toggleFavoriteFromNotification(): Boolean {
        val account = sessionStore.session.first() ?: return false
        val entry = queueCommandMutex.withLock { currentState.currentEntry } ?: return false
        val favorite = !entry.track.favorite
        updateFavorite(entry.track.id, favorite)
        return try {
            repository.setFavorite(account, entry.track.id, favorite)
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            updateFavorite(entry.track.id, !favorite)
            throw error
        } catch (error: Throwable) {
            Log.w(AUDIO_TAG, "notification favorite update failed", error)
            updateFavorite(entry.track.id, !favorite)
            false
        }
    }

    private data class ProgressSample(
        val itemId: String,
        val positionSeconds: Double,
        val durationSeconds: Double?,
        val paused: Boolean,
    )

    private val progressReportingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun captureProgressSample(paused: Boolean = !player.isPlaying): ProgressSample? {
        val entry = currentState.currentEntry ?: return null
        return ProgressSample(
            itemId = entry.track.id,
            positionSeconds = observedPlayerPositionMs().coerceAtLeast(0L) / 1_000.0,
            durationSeconds = currentState.durationSeconds.toDouble().takeIf { it > 0 },
            paused = paused,
        )
    }

    private fun enqueueProgress(sample: ProgressSample) {
        progressReportingScope.launch {
            progressMutex.withLock { sendProgress(sample) }
        }
    }

    private suspend fun reportProgress() {
        captureProgressSample()?.let { sample ->
            progressMutex.withLock { sendProgress(sample) }
        }
    }

    private suspend fun sendProgress(sample: ProgressSample) {
        val account = sessionStore.session.first() ?: return
        if (!sessionStore.watchHistoryEnabled.first()) return
        try {
            repository.reportPlayback(
                account,
                sample.itemId,
                sample.positionSeconds,
                sample.paused,
                null,
                sample.durationSeconds,
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: CatalogException) {
            if (error.statusCode == 401) repository.clearSessionIfCurrent(account)
        } catch (error: Throwable) {
            Log.w(AUDIO_TAG, "Audio progress reporting failed", error)
        }
    }

    private fun persistSnapshot() {
        val epoch = ++persistenceEpoch
        persistJob?.cancel()
        persistJob = serviceScope.launch {
            delay(PERSIST_DEBOUNCE_MILLIS)
            if (epoch != persistenceEpoch) return@launch
            val account = sessionStore.session.first() ?: return@launch
            if (epoch != persistenceEpoch) return@launch
            val snapshot = queueCommandMutex.withLock {
                if (epoch != persistenceEpoch || currentState.queue.isEmpty()) null
                else currentState.toSnapshot(account)
            }
            if (snapshot == null) return@launch
            persistenceMutex.withLock {
                if (epoch == persistenceEpoch) sessionStore.saveAudioQueueSnapshot(snapshot)
            }
        }
    }

    private suspend fun persistSnapshotNow(accountOverride: AuthSession? = null) {
        val account = accountOverride ?: sessionStore.session.first() ?: return
        val snapshot = queueCommandMutex.withLock {
            if (currentState.queue.isEmpty()) null else currentState.toSnapshot(account)
        }
        persistenceMutex.withLock {
            if (snapshot == null) {
                sessionStore.clearAudioQueueSnapshot(account.serverUrl, account.userId)
            } else {
                sessionStore.saveAudioQueueSnapshot(snapshot)
            }
        }
    }

    private suspend fun clearPersistedSnapshot(account: AuthSession) {
        persistenceMutex.withLock {
            sessionStore.clearAudioQueueSnapshot(account.serverUrl, account.userId)
        }
    }

    private fun publishAudioState() {
        AudioServiceBridge.publish(currentState)
        refreshNotificationControls()
    }

    private fun publishPlayerState() {
        val duration =
            player.duration
                .takeIf { playerOwnsCurrentQueueEntry() && it != C.TIME_UNSET && it > 0 }
                ?.div(1_000L) ?: currentState.durationSeconds
        val positionMillis = observedPlayerPositionMs()
        val position = positionMillis.div(1_000L)
        currentState =
            currentState.copy(
                positionSeconds = position,
                positionMillis = positionMillis,
                positionUpdatedAtElapsedRealtime = SystemClock.elapsedRealtime(),
                durationSeconds = duration,
                isPlaying = player.isPlaying,
                isLoading = loadingCurrent,
            )
        publishAudioState()
    }

    private val playerListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && activePlayerGeneration != firstPlayingGeneration) {
                    firstPlayingGeneration = activePlayerGeneration
                    Log.i(
                        AUDIO_TAG,
                        "audio first isPlaying generation=${activePlayerGeneration} entry=${currentState.currentEntry?.entryId}",
                    )
                }
                publishPlayerState()
                if (!isPlaying)
                    serviceScope.launch {
                        reportProgress()
                        persistSnapshot()
                    }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (activePlayerGeneration == transactionController.currentGeneration()) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            loadingCurrent = false
                            Log.i(
                                AUDIO_TAG,
                                "audio media ready generation=$activePlayerGeneration entry=${currentState.currentEntry?.entryId}",
                            )
                        }
                        Player.STATE_ENDED -> loadingCurrent = false
                        Player.STATE_BUFFERING,
                        Player.STATE_IDLE ->
                            if (player.currentMediaItem != null) loadingCurrent = true
                    }
                }
                publishPlayerState()
                if (playbackState == Player.STATE_ENDED && !loadingCurrent && !suppressEnded) {
                    serviceScope.launch {
                        advance(force = true, commandSequence = 0L)
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                val entryId = currentState.currentEntry?.entryId
                if (newPosition.mediaItem?.mediaId != "zenstream:queue:$entryId") return
                val positionMs = newPosition.positionMs.coerceAtLeast(0L)
                rememberPosition(entryId, positionMs)
                publishPlayerState()
                serviceScope.launch { persistSnapshot() }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val mediaId = mediaItem?.mediaId.orEmpty()
                if (mediaId.startsWith("zenstream:track:")) {
                    autoAdoptionJob?.cancel()
                    val generation = transactionController.currentGeneration()
                    autoAdoptionJob = serviceScope.launch {
                        adoptAutoTrack(
                            mediaId.removePrefix("zenstream:track:"),
                            generation,
                        )
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                audioDiagnostics.playerError(error)
                if (activePlayerGeneration != transactionController.currentGeneration()) return
                val entryId = currentState.currentEntry?.entryId
                if (transactionController.activeRecoveryJob?.isActive == true) return
                if (entryId != null && retryEntryId != entryId) {
                    val resumePositionMs = recoveryPositionMs(entryId)
                    retryEntryId = entryId
                    val autoplay = lastAutoplay
                    audioDiagnostics.recoveryScheduled(resumePositionMs)
                    val request =
                        acceptPlaybackSelection(
                            commandSequence = 0L,
                            entryId = entryId,
                            autoPlay = autoplay,
                            positionMs = resumePositionMs,
                        ) ?: return
                    val recovery = serviceScope.launch {
                        try {
                            queueCommandMutex.withLock {
                                if (
                                    !transactionController.isCurrent(
                                        request,
                                        currentState.currentEntry?.entryId,
                                    )
                                ) {
                                    return@withLock
                                }
                                loadingCurrent = true
                                activeAudioSource = null
                                suppressEnded = true
                                player.stop()
                                player.clearMediaItems()
                                currentState =
                                    currentState.copy(
                                        isLoading = true,
                                        isPlaying = false,
                                        error = null,
                                    )
                                publishAudioState()
                            }
                            launchLoad(request)
                        } finally {
                            transactionController.clearRecovery(
                                kotlinx.coroutines.currentCoroutineContext()[Job]!!
                            )
                        }
                    }
                    transactionController.registerRecovery(recovery)
                } else {
                    loadingCurrent = false
                    activeAudioSource = null
                    activePlayerGeneration = null
                    currentState =
                        currentState.copy(
                            isLoading = false,
                            error = error.message ?: "Audio decoder error",
                        )
                    publishAudioState()
                }
            }
        }

    private suspend fun loadAutoCatalog() {
        autoCatalogMutex.withLock {
            val account = authenticatedAccount()
            if (account == null) {
                catalogScope = null
                catalogSession = null
                catalogLibraries.clear()
                catalogAlbums.clear()
                catalogArtists.clear()
                catalogTracks.clear()
                catalogArtistAlbums.clear()
                catalogArtistTracks.clear()
                return
            }
            val scope = accountScope(account)
            if (scope == catalogScope) {
                // The account may have received a new bearer/resource ticket while its
                // catalog remains valid. Keep Auto artwork and playback authenticated.
                catalogSession = account
                return
            }
            try {
                catalogAlbums.clear()
                catalogArtists.clear()
                catalogTracks.clear()
                catalogArtistAlbums.clear()
                catalogArtistTracks.clear()
                val libraries =
                    repository.libraries(account).filter { it.collectionType == "music" }
                catalogLibraries.clear()
                catalogLibraries += libraries
                for (library in libraries) {
                    val albums = mutableListOf<CatalogMediaItem>()
                    var start = 0
                    while (albums.size < AUTO_MAX_ALBUMS) {
                        val page =
                            repository.libraryPage(
                                account,
                                library,
                                start,
                                AUTO_PAGE_SIZE,
                                LibrarySort(LibrarySortBy.Title, SortOrder.Ascending),
                            )
                        val pageItems = page.items.distinctBy { it.id }
                        if (pageItems.isEmpty()) break
                        albums += pageItems
                        val nextStart = start + page.items.size
                        if (page.items.size < AUTO_PAGE_SIZE || nextStart >= page.totalRecordCount)
                            break
                        start = nextStart
                    }
                    val uniqueAlbums = albums.distinctBy { it.id }.take(AUTO_MAX_ALBUMS)
                    catalogAlbums[library.id] = uniqueAlbums
                    catalogArtists[library.id] =
                        uniqueAlbums
                            .flatMap { album ->
                                val credited =
                                    album.artistCredits.mapNotNull { credit ->
                                        credit.id?.let { id ->
                                            CatalogMediaItem(
                                                id = id,
                                                name = credit.name,
                                                type = "MusicArtist",
                                            )
                                        }
                                    }
                                credited +
                                    listOfNotNull(
                                        album.artistId?.let { id ->
                                            CatalogMediaItem(
                                                id = id,
                                                name = album.albumArtist ?: "Artist",
                                                type = "MusicArtist",
                                            )
                                        }
                                    )
                            }
                            .distinctBy { it.id }
                    uniqueAlbums.forEach { album ->
                        val tracks =
                            repository.musicAlbum(account, album.id).tracks.distinctBy { it.id }
                        catalogTracks[album.id] = tracks
                    }
                }
                val allAlbums = catalogAlbums.values.flatten().distinctBy { it.id }
                val allTracks = catalogTracks.values.flatten().distinctBy { it.id }
                val artistIds =
                    (catalogArtists.values.flatten().map { it.id } +
                            allAlbums.flatMap { album ->
                                album.artistCredits.mapNotNull { it.id } +
                                    listOfNotNull(album.artistId)
                            })
                        .distinct()
                        .take(AUTO_ARTIST_LIMIT)
                artistIds.forEach { artistId ->
                    catalogArtistAlbums[artistId] = allAlbums.filter { album ->
                        album.artistId == artistId || album.artistCredits.any { it.id == artistId }
                    }
                    catalogArtistTracks[artistId] = allTracks.filter { track ->
                        track.artistId == artistId || track.artistCredits.any { it.id == artistId }
                    }
                }
                catalogSession = account
                catalogScope = scope
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Android Auto can retry its browse request after a transient
                // catalog outage; keep the previous account-scoped cache
                // invalidated instead of caching a partial tree.
                catalogScope = null
                catalogSession = null
            }
        }
    }

    /** Rehydrates artwork for queue snapshots written before artwork metadata was persisted. */
    private suspend fun applyQueueArtworkFallbacks() {
        if (currentState.queue.isEmpty()) return
        val albumsById =
            catalogAlbums.values.asSequence().flatten().distinctBy { it.id }.associateBy { it.id }
        if (albumsById.isEmpty()) return
        val updated =
            currentState.queue.map { entry ->
                entry.copy(
                    track =
                        entry.track.withPrimaryArtworkFallback(
                            entry.track.albumId?.let(albumsById::get)
                        )
                )
            }
        if (updated == currentState.queue) return
        currentState = currentState.copy(queue = updated)
        publishPlayerState()
        persistSnapshot()
    }

    private fun parseSnapshot(encoded: String): AudioQueueSnapshot? =
        runCatching { audioQueueSnapshotFromJson(JSONObject(encoded)) }.getOrNull()

    private suspend fun adoptAutoTrack(trackId: String, expectedGeneration: Long) {
        val mediaId = "zenstream:track:$trackId"
        if (
            transactionController.currentGeneration() != expectedGeneration ||
                player.currentMediaItem?.mediaId != mediaId
        ) {
            return
        }
        val account = sessionStore.session.first() ?: return
        loadAutoCatalog()
        if (
            transactionController.currentGeneration() != expectedGeneration ||
                player.currentMediaItem?.mediaId != mediaId
        ) {
            return
        }
        val track =
            catalogTracks.values.asSequence().flatten().firstOrNull { it.id == trackId } ?: return
        val albumTracks =
            track.albumId
                ?.let { catalogTracks[it].orEmpty() }
                .orEmpty()
                .ifEmpty { listOf(track) }
                .distinctBy { it.id }
        val entries = albumTracks.map { AudioQueueEntry(UUID.randomUUID().toString(), it) }
        val currentIndex = entries.indexOfFirst { it.track.id == track.id }.coerceAtLeast(0)
        var telemetryEntry: AudioQueueEntry? = null
        queueCommandMutex.withLock {
            if (
                transactionController.currentGeneration() != expectedGeneration ||
                    player.currentMediaItem?.mediaId != mediaId
            ) {
                return@withLock
            }
            transactionController.invalidate()
            currentState =
                AudioPlayerState(
                    queue = entries,
                    currentIndex = currentIndex,
                    durationSeconds = track.durationSeconds?.toLong()?.coerceAtLeast(0L) ?: 0L,
                    isPlaying = player.isPlaying,
                    volume = currentState.volume,
                    muted = currentState.muted,
                    shuffle = currentState.shuffle,
                    repeatMode = currentState.repeatMode,
                )
            playerScope = accountScope(account)
            telemetryEntry = currentState.currentEntry
            currentState.currentEntry?.let {
                currentState = markQueueEntryPlayed(currentState, it.entryId) ?: currentState
            }
            publishPlayerState()
        }
        persistSnapshot()
        telemetryEntry?.let { launchPlayStart(account, it) }
    }

    private fun snapshotBelongsTo(snapshot: AudioQueueSnapshot, account: AuthSession): Boolean =
        audioQueueScope(snapshot.serverUrl, snapshot.userId) ==
            audioQueueScope(account.serverUrl, account.userId)

    private fun accountScope(account: AuthSession): String =
        audioQueueScope(account.serverUrl, account.userId)

    /**
     * MediaSession artwork is fetched outside the normal Coil request path, so it needs the
     * short-lived artwork capability from the latest authenticated account response. Existing
     * sessions created before that capability was added are refreshed once on demand; if the
     * refresh is unavailable, the bearer-authenticated request path still remains usable.
     */
    private suspend fun authenticatedAccount(): AuthSession? {
        val account = sessionStore.session.first() ?: return null
        if (!account.artworkTicket.isNullOrBlank()) return account
        return try {
            repository.refreshCurrentAccount()
            sessionStore.session.first() ?: account
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Throwable) {
            account
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        serviceScope.launch {
            reportProgress()
            persistSnapshot()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persistJob?.cancel()
        if (!sessionDetached) {
            runBlocking {
                withTimeoutOrNull(SHUTDOWN_FLUSH_TIMEOUT_MILLIS) {
                    reportProgress()
                    persistSnapshotNow()
                }
            }
        }
        positionJob?.cancel()
        progressJob?.cancel()
        lyricsPrefetchJob?.cancel()
        autoAdoptionJob?.cancel()
        cancelRecovery()
        restoreValidationJob?.cancel()
        transactionController.activeLoadJob?.cancel()
        if (!sessionDetached) librarySession?.release()
        player.removeListener(playerListener)
        player.removeAnalyticsListener(audioDiagnostics)
        player.clearMediaItems()
        player.release()
        serviceScope.coroutineContext.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * Restore is issued when the app starts, before there is necessarily a current MediaItem for
     * Media3's own notification provider. Starting foreground work immediately prevents Android's
     * foreground-service timeout from turning a normal cold start into an ANR. Media3 updates its
     * session notification once the player has queue metadata.
     */
    private fun startServiceForeground() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                        AudioNotificationConfig.CHANNEL_ID,
                        getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply {
                        description = "Audio playback controls"
                        setShowBadge(false)
                    }
            )
        }
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(this, AudioNotificationConfig.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("Audio player ready")
                .setContentIntent(contentIntent)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        ServiceCompat.startForeground(
            this,
            AudioNotificationConfig.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val result = SettableFuture.create<MediaSession.ConnectionResult>()
            val accepted = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (session.isMediaNotificationController(controller)) {
                val availableSessionCommands =
                    (if (controller.isTrusted)
                            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                        else
                            MediaSession.ConnectionResult
                                .DEFAULT_UNTRUSTED_SESSION_AND_LIBRARY_COMMANDS)
                        .buildUpon()
                        .add(
                            SessionCommand(
                                AudioNotificationControls.ACTION_TOGGLE_FAVORITE,
                                Bundle.EMPTY,
                            )
                        )
                        .add(
                            SessionCommand(
                                AudioNotificationControls.ACTION_PREVIOUS,
                                Bundle.EMPTY,
                            )
                        )
                        .add(
                            SessionCommand(
                                AudioNotificationControls.ACTION_NEXT,
                                Bundle.EMPTY,
                            )
                        )
                        .add(
                            SessionCommand(
                                AudioNotificationControls.ACTION_STOP,
                                Bundle.EMPTY,
                            )
                        )
                        .build()
                accepted
                    .setAvailableSessionCommands(availableSessionCommands)
                    .setMediaButtonPreferences(notificationButtonPreferences())
            }
            result.set(accepted.build())
            return result
        }

        override fun onPostConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ) {
            if (session.isMediaNotificationController(controller)) {
                mediaNotificationController = controller
                session.setMediaButtonPreferences(controller, notificationButtonPreferences())
                refreshNotificationControls(force = true)
            }
        }

        override fun onDisconnected(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ) {
            if (mediaNotificationController === controller) {
                mediaNotificationController = null
                lastNotificationEntryId = null
                lastNotificationFavorite = null
                lastNotificationPlaying = null
            }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (AudioNotificationControls.controlForAction(customCommand.customAction) == null) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }

            val result = SettableFuture.create<SessionResult>()
            serviceScope.launch {
                try {
                    val resultCode =
                        when (customCommand.customAction) {
                            AudioNotificationControls.ACTION_TOGGLE_FAVORITE ->
                                if (toggleFavoriteFromNotification()) SessionResult.RESULT_SUCCESS
                                else SessionResult.RESULT_ERROR_UNKNOWN
                            AudioNotificationControls.ACTION_PREVIOUS -> {
                                previous(commandSequence = 0L)
                                SessionResult.RESULT_SUCCESS
                            }
                            AudioNotificationControls.ACTION_NEXT -> {
                                advance(force = false, commandSequence = 0L)
                                SessionResult.RESULT_SUCCESS
                            }
                            AudioNotificationControls.ACTION_STOP -> {
                                clearQueue(commandSequence = 0L)
                                SessionResult.RESULT_SUCCESS
                            }
                            else -> SessionResult.RESULT_ERROR_NOT_SUPPORTED
                        }
                    result.set(SessionResult(resultCode))
                } catch (error: kotlinx.coroutines.CancellationException) {
                    result.setException(error)
                    throw error
                } catch (error: Throwable) {
                    Log.w(AUDIO_TAG, "notification command failed", error)
                    result.set(SessionResult(SessionError.ERROR_UNKNOWN))
                }
            }
            return result
        }

        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int =
            when (playerCommand) {
                Player.COMMAND_PLAY_PAUSE -> {
                    serviceScope.launch { togglePlayback() }
                    Player.COMMAND_INVALID
                }
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    serviceScope.launch { advance(force = false, commandSequence = 0L) }
                    Player.COMMAND_INVALID
                }
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    serviceScope.launch { previous(commandSequence = 0L) }
                    Player.COMMAND_INVALID
                }
                Player.COMMAND_STOP -> {
                    serviceScope.launch { clearQueue(commandSequence = 0L) }
                    Player.COMMAND_INVALID
                }
                else -> super.onPlayerCommandRequest(session, controller, playerCommand)
            }

        override fun onAddMediaItems(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val result = SettableFuture.create<List<MediaItem>>()
            serviceScope.launch {
                try {
                    if (sessionStore.session.first() == null) {
                        result.setException(SecurityException("Sign in to play music"))
                    } else {
                        loadAutoCatalog()
                        result.set(mediaItems.map { autoPlaybackItem(it) })
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    result.setException(error)
                    throw error
                } catch (error: Throwable) {
                    result.setException(error)
                }
            }
            return result
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val result = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch {
                try {
                    if (sessionStore.session.first() == null) {
                        result.set(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
                    } else {
                        loadAutoCatalog()
                        result.set(
                            LibraryResult.ofItem(
                                autoItem("zenstream:root", "ZenStream Music", false, true),
                                params,
                            )
                        )
                    }
                } catch (error: Throwable) {
                    result.setException(error)
                }
            }
            return result
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val result = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                try {
                    if (sessionStore.session.first() == null) {
                        result.set(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
                        return@launch
                    }
                    loadAutoCatalog()
                    val children = autoChildren(parentId)
                    val safePageSize = pageSize.coerceAtLeast(1)
                    val from = (page.coerceAtLeast(0) * safePageSize).coerceAtMost(children.size)
                    val to = (from + safePageSize).coerceAtMost(children.size)
                    result.set(
                        LibraryResult.ofItemList(
                            ImmutableList.copyOf(children.subList(from, to)),
                            params,
                        )
                    )
                } catch (error: Throwable) {
                    result.setException(error)
                }
            }
            return result
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val result = SettableFuture.create<LibraryResult<MediaItem>>()
            serviceScope.launch {
                try {
                    if (sessionStore.session.first() == null) {
                        result.set(LibraryResult.ofError(SessionError.ERROR_PERMISSION_DENIED))
                    } else {
                        loadAutoCatalog()
                        result.set(
                            autoItemForId(mediaId)?.let { LibraryResult.ofItem(it, null) }
                                ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
                        )
                    }
                } catch (error: Throwable) {
                    result.setException(error)
                }
            }
            return result
        }
    }

    private suspend fun autoPlaybackItem(request: MediaItem): MediaItem {
        val trackId =
            request.mediaId
                .takeIf { it.startsWith("zenstream:track:") }
                ?.removePrefix("zenstream:track:")
                ?.takeIf(String::isNotBlank)
                ?: error("Android Auto requested an unsupported media id")
        val track =
            catalogTracks.values.asSequence().flatten().firstOrNull { it.id == trackId }
                ?: error("Music track is no longer available")
        val account = authenticatedAccount() ?: error("Sign in to play music")
        httpFactory.setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${account.token}"))
        val data =
            repository.playback(account, track.id, PlaybackOptions(engine = PlayerEngine.MEDIA3))
        val candidateUrl =
            data.url ?: data.source.url ?: error("Server did not return an audio URL")
        val url = resolveSameOriginUrl(account.serverUrl, candidateUrl)
        val normalizedSource =
            normalizeAudioSource(
                url = url,
                mimeType = data.mimeType,
                mode = data.mode,
                sessionId = data.sessionId,
                durationSeconds = data.durationSeconds ?: data.source.durationSeconds,
                expiresAt = data.expiresAt,
            )
        val metadata =
            MediaMetadata.Builder()
                .setTitle(track.name)
                .setArtist(primaryArtist(track))
                .setAlbumTitle(track.album)
                .apply { authenticatedArtworkUri(track, account)?.let { setArtworkUri(it) } }
                .build()
        return MediaItem.Builder()
            .setMediaId(request.mediaId)
            .setUri(Uri.parse(url))
            .apply { normalizedSource.mimeType?.let(::setMimeType) }
            .setMediaMetadata(metadata)
            .build()
    }

    private fun autoChildren(parentId: String): List<MediaItem> =
        when {
            parentId == "zenstream:root" ->
                catalogLibraries.map {
                    autoItem("zenstream:library:${it.id}", it.name, false, true)
                }
            parentId.startsWith("zenstream:library:") -> {
                val libraryId = parentId.removePrefix("zenstream:library:")
                listOf(
                    autoItem("zenstream:albums:$libraryId", "Albums", false, true),
                    autoItem("zenstream:artists:$libraryId", "Artists", false, true),
                )
            }
            parentId.startsWith("zenstream:albums:") ->
                catalogAlbums[parentId.removePrefix("zenstream:albums:")].orEmpty().map {
                    autoItem("zenstream:album:${it.id}", it.name, false, true, it)
                }
            parentId.startsWith("zenstream:artists:") ->
                catalogArtists[parentId.removePrefix("zenstream:artists:")].orEmpty().map {
                    autoItem("zenstream:artist:${it.id}", it.name, false, true, it)
                }
            parentId.startsWith("zenstream:album:") ->
                catalogTracks[parentId.removePrefix("zenstream:album:")].orEmpty().map {
                    autoItem("zenstream:track:${it.id}", it.name, true, false, it)
                }
            parentId.startsWith("zenstream:artist:") -> {
                val artistId = parentId.removePrefix("zenstream:artist:")
                val releases =
                    catalogArtistAlbums[artistId].orEmpty().map {
                        autoItem("zenstream:album:${it.id}", it.name, false, true, it)
                    }
                val tracks =
                    catalogArtistTracks[artistId].orEmpty().map {
                        autoItem("zenstream:track:${it.id}", it.name, true, false, it)
                    }
                (releases + tracks).distinctBy { it.mediaId }
            }
            else -> emptyList()
        }

    private fun autoItem(
        id: String,
        title: String,
        playable: Boolean,
        browsable: Boolean,
        catalogItem: CatalogMediaItem? = null,
    ): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(catalogItem?.albumArtist)
                    .setAlbumTitle(catalogItem?.album)
                    .setIsPlayable(playable)
                    .setIsBrowsable(browsable)
                    .apply {
                        authenticatedArtworkUri(catalogItem)?.let(::setArtworkUri)
                    }
                    .build()
            )
            .build()

    private fun authenticatedArtworkUri(
        item: CatalogMediaItem?,
        account: AuthSession? = catalogSession,
    ): Uri? {
        account ?: return null
        val ticket =
            (account.artworkTicket ?: account.resourceTicket)?.takeIf(String::isNotBlank)
                ?: return null
        val artworkItem =
            item?.withPrimaryArtworkFallback(
                item.albumId?.let { albumId ->
                    catalogAlbums.values.asSequence().flatten().firstOrNull { it.id == albumId }
                }
            )
        val path =
            artworkItem?.imageTags?.get("Primary")?.takeIf { it.startsWith("/api/") } ?: return null
        return runCatching {
                val absolute = resolveSameOriginUrl(account.serverUrl, path)
                Uri.parse(
                    absolute
                        .toHttpUrl()
                        .newBuilder()
                        .addQueryParameter("access", ticket)
                        .build()
                        .toString()
                )
            }
            .getOrNull()
    }

    private fun autoItemForId(mediaId: String): MediaItem? {
        return when {
            mediaId == "zenstream:root" -> autoItem(mediaId, "ZenStream Music", false, true)
            mediaId.startsWith("zenstream:library:") -> {
                val libraryId = mediaId.removePrefix("zenstream:library:")
                catalogLibraries
                    .firstOrNull { it.id == libraryId }
                    ?.let {
                        autoItem(mediaId, it.name, false, true)
                    }
            }
            mediaId.startsWith("zenstream:albums:") -> {
                val libraryId = mediaId.removePrefix("zenstream:albums:")
                catalogLibraries
                    .firstOrNull { it.id == libraryId }
                    ?.let {
                        autoItem(mediaId, "Albums", false, true)
                    }
            }
            mediaId.startsWith("zenstream:artists:") -> {
                val libraryId = mediaId.removePrefix("zenstream:artists:")
                catalogLibraries
                    .firstOrNull { it.id == libraryId }
                    ?.let {
                        autoItem(mediaId, "Artists", false, true)
                    }
            }
            mediaId.startsWith("zenstream:track:") -> {
                val id = mediaId.removePrefix("zenstream:track:")
                catalogTracks.values
                    .flatten()
                    .firstOrNull { it.id == id }
                    ?.let {
                        autoItem(mediaId, it.name, true, false, it)
                    }
            }
            mediaId.startsWith("zenstream:album:") -> {
                val id = mediaId.removePrefix("zenstream:album:")
                catalogAlbums.values
                    .flatten()
                    .firstOrNull { it.id == id }
                    ?.let {
                        autoItem(mediaId, it.name, false, true, it)
                    }
            }
            mediaId.startsWith("zenstream:artist:") -> {
                val id = mediaId.removePrefix("zenstream:artist:")
                (catalogArtists.values.flatten() + catalogMediaArtists())
                    .firstOrNull { it.id == id }
                    ?.let {
                        autoItem(mediaId, it.name, false, true, it)
                    }
            }
            else -> null
        }
    }

    private fun catalogMediaArtists(): List<CatalogMediaItem> =
        catalogArtistAlbums.keys.map { id ->
            catalogArtists.values.flatten().firstOrNull { it.id == id }
                ?: CatalogMediaItem(id = id, name = id, type = "MusicArtist")
        }

    private fun AudioQueueSnapshot.toPlayerState(): AudioPlayerState =
        AudioPlayerState(
            queue = entries,
            currentIndex =
                if (entries.isEmpty()) -1 else currentIndex.coerceIn(0, entries.lastIndex),
            positionSeconds = positionSeconds.toLong(),
            durationSeconds =
                (durationSeconds
                        ?: entries
                            .getOrNull(currentIndex.coerceIn(0, entries.lastIndex.coerceAtLeast(0)))
                            ?.track
                            ?.durationSeconds)
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?.toLong() ?: 0L,
            shuffle = shuffle,
            repeatMode = repeatMode,
            sourceFormat =
                sourceFormat.takeIf { sourceEntryId == entries.getOrNull(currentIndex)?.entryId },
            sourceBitrate =
                sourceBitrate.takeIf { sourceEntryId == entries.getOrNull(currentIndex)?.entryId },
            sourceSampleRate =
                sourceSampleRate.takeIf {
                    sourceEntryId == entries.getOrNull(currentIndex)?.entryId
                },
            playbackMode =
                playbackMode.takeIf { sourceEntryId == entries.getOrNull(currentIndex)?.entryId },
            playedEntryIds = playedEntryIds,
        )

    private fun AudioPlayerState.toSnapshot(account: AuthSession): AudioQueueSnapshot =
        AudioQueueSnapshot(
            serverUrl = account.serverUrl,
            userId = account.userId,
            entries = queue,
            currentIndex = currentIndex.coerceIn(0, queue.lastIndex.coerceAtLeast(0)),
            positionSeconds = positionSeconds.toDouble(),
            shuffle = shuffle,
            repeatMode = repeatMode,
            playedEntryIds = playedEntryIds,
            durationSeconds = durationSeconds.toDouble().takeIf { it > 0L },
            sourceEntryId = currentEntry?.entryId,
            sourceFormat = sourceFormat,
            sourceBitrate = sourceBitrate,
            sourceSampleRate = sourceSampleRate,
            playbackMode = playbackMode,
        )

    companion object {
        private const val AUDIO_TAG = "ZenStreamAudio"
        private const val PROGRESS_INTERVAL_MILLIS = 10_000L
        private const val PERSIST_DEBOUNCE_MILLIS = 250L
        private const val SHUTDOWN_FLUSH_TIMEOUT_MILLIS = 2_000L
        private const val PLAY_START_ATTEMPTS = 3
        private const val PLAY_START_RETRY_DELAY_MILLIS = 250L
        private const val AUTO_PAGE_SIZE = 100
        private const val AUTO_MAX_ALBUMS = 1_000
        private const val AUTO_ARTIST_LIMIT = 100

        private fun primaryArtist(item: CatalogMediaItem): String =
            item.artistCredits.firstOrNull()?.name
                ?: item.albumArtist
                ?: item.artists.firstOrNull()
                ?: "Unknown artist"

        private fun resolveSameOriginUrl(server: String, candidate: String): String {
            val base = server.toHttpUrl()
            val value = base.resolve(candidate) ?: error("Invalid playback URL")
            require(
                value.scheme == base.scheme && value.host == base.host && value.port == base.port
            ) {
                "Rejected cross-origin playback URL"
            }
            return value.toString()
        }
    }
}

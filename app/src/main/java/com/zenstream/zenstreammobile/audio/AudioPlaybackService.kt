package com.zenstream.zenstreammobile.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
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
    private lateinit var librarySession: MediaLibrarySession
    private lateinit var repository: CatalogRepository
    private lateinit var sessionStore: SessionStore
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val progressMutex = Mutex()
    private val autoCatalogMutex = Mutex()
    private var progressJob: Job? = null
    private var positionJob: Job? = null
    private var persistJob: Job? = null
    private var currentState = AudioPlayerState()
    private var retryEntryId: String? = null
    private var loadingCurrent = false
    private var lastAutoplay = false
    private var suppressEnded = false
    private var playerScope: String? = null

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
        startServiceForeground()
        sessionStore = SessionStore(applicationContext)
        repository = CatalogRepository(CatalogApi(), sessionStore)
        httpFactory = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(false)
        dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        player =
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(AudioMediaSourceFactory(dataSourceFactory))
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
        val sessionActivity =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        librarySession =
            MediaLibrarySession.Builder(this, player, LibraryCallback())
                .setSessionActivity(sessionActivity)
                .build()
        serviceScope.launch { restoreForCurrentAccount() }
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
        librarySession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            AudioServiceBridge.ACTION_PLAY_QUEUE ->
                intent.getStringExtra(AudioServiceBridge.EXTRA_SNAPSHOT)?.let { encoded ->
                    serviceScope.launch { replaceQueue(encoded) }
                }
            AudioServiceBridge.ACTION_ADD_QUEUE ->
                intent.getStringExtra(AudioServiceBridge.EXTRA_SNAPSHOT)?.let { encoded ->
                    serviceScope.launch { addQueue(encoded) }
                }
            AudioServiceBridge.ACTION_TOGGLE_PLAYBACK -> serviceScope.launch { togglePlayback() }
            AudioServiceBridge.ACTION_NEXT -> serviceScope.launch { advance(force = false) }
            AudioServiceBridge.ACTION_PREVIOUS -> serviceScope.launch { previous() }
            AudioServiceBridge.ACTION_TOGGLE_SHUFFLE -> serviceScope.launch { toggleShuffle() }
            AudioServiceBridge.ACTION_TOGGLE_REPEAT -> serviceScope.launch { toggleRepeat() }
            AudioServiceBridge.ACTION_SET_VOLUME -> serviceScope.launch { setVolume() }
            AudioServiceBridge.ACTION_TOGGLE_MUTE -> serviceScope.launch { toggleMute() }
            AudioServiceBridge.ACTION_RETRY -> serviceScope.launch { retryCurrent() }
            AudioServiceBridge.ACTION_SEEK -> {
                val position = intent.getLongExtra(AudioServiceBridge.EXTRA_POSITION, 0L)
                serviceScope.launch { seekTo(position) }
            }
            AudioServiceBridge.ACTION_REMOVE_QUEUE ->
                serviceScope.launch {
                    removeQueue(intent.getStringExtra(AudioServiceBridge.EXTRA_ENTRY_ID))
                }
            AudioServiceBridge.ACTION_REORDER_QUEUE ->
                serviceScope.launch {
                    reorderQueue(
                        intent.getIntExtra(AudioServiceBridge.EXTRA_FROM, -1),
                        intent.getIntExtra(AudioServiceBridge.EXTRA_TO, -1),
                    )
                }
            AudioServiceBridge.ACTION_CLEAR_QUEUE -> serviceScope.launch { clearQueue() }
            AudioServiceBridge.ACTION_PAUSE_FOR_VIDEO -> serviceScope.launch { pauseForVideo() }
            AudioServiceBridge.ACTION_UPDATE_FAVORITE -> {
                val itemId = intent.getStringExtra(AudioServiceBridge.EXTRA_ITEM_ID)
                val favorite = intent.getBooleanExtra(AudioServiceBridge.EXTRA_FAVORITE, false)
                serviceScope.launch { updateFavorite(itemId, favorite) }
            }
            AudioServiceBridge.ACTION_RESTORE -> serviceScope.launch { restoreForCurrentAccount() }
            null -> Unit
        }
        return START_STICKY
    }

    private suspend fun restoreQueue() {
        val account = sessionStore.session.first() ?: return
        val snapshot =
            sessionStore.loadAudioQueueSnapshot(account.serverUrl, account.userId) ?: return
        if (!snapshotBelongsTo(snapshot, account)) return
        val validated = revalidateQueue(account, snapshot)
        if (validated == null || validated.entries.isEmpty()) {
            sessionStore.clearAudioQueueSnapshot(account.serverUrl, account.userId)
            return
        }
        if (validated.entries.size != snapshot.entries.size) {
            sessionStore.saveAudioQueueSnapshot(validated)
        }
        currentState = validated.toPlayerState()
        AudioServiceBridge.publish(currentState)
        // Restoration is intentionally paused. The user must press play.
    }

    private suspend fun restoreForCurrentAccount() {
        val account = sessionStore.session.first()
        val scope = account?.let { accountScope(it) }
        if (scope == playerScope && account != null) {
            loadAutoCatalog()
            return
        }
        persistJob?.cancel()
        suppressEnded = true
        player.stop()
        retryEntryId = null
        currentState = AudioPlayerState()
        playerScope = scope
        AudioServiceBridge.publish(currentState)
        if (account != null) {
            restoreQueue()
            restoreAudioPreferences()
        }
        loadAutoCatalog()
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
        player.volume = 1f
        currentState =
            currentState.copy(
                volume = 1f,
                muted = false,
                shuffle = shuffle,
                repeatMode = repeatMode,
            )
        AudioServiceBridge.publish(currentState)
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
        return snapshot.copy(entries = retained, currentIndex = currentIndex)
    }

    private suspend fun replaceQueue(encoded: String) {
        val snapshot = parseSnapshot(encoded) ?: return
        val account = sessionStore.session.first() ?: return
        if (!snapshotBelongsTo(snapshot, account)) return
        sessionStore.saveAudioShuffle(snapshot.shuffle)
        sessionStore.saveAudioRepeatMode(snapshot.repeatMode)
        currentState = snapshot.toPlayerState().copy(isLoading = true, error = null)
        AudioServiceBridge.publish(currentState)
        suppressEnded = true
        player.stop()
        retryEntryId = null
        lastAutoplay = true
        persistSnapshot()
        loadCurrent(autoPlay = true)
    }

    private suspend fun addQueue(encoded: String) {
        val snapshot = parseSnapshot(encoded) ?: return
        val additions = snapshot.entries
        if (additions.isEmpty()) return
        val account = sessionStore.session.first() ?: return
        if (!snapshotBelongsTo(snapshot, account)) return
        val wasEmpty = currentState.queue.isEmpty()
        val merged = (currentState.queue + additions).distinctBy { it.entryId }
        currentState =
            currentState.copy(
                queue = merged,
                currentIndex =
                    if (wasEmpty) 0 else currentState.currentIndex.coerceIn(0, merged.lastIndex),
                error = null,
            )
        publishPlayerState()
        persistSnapshot()
    }

    private suspend fun togglePlayback() {
        if (currentState.queue.isEmpty()) return
        if (player.isPlaying) {
            player.pause()
            reportProgress()
        } else {
            if (player.currentMediaItem == null) loadCurrent(autoPlay = true) else player.play()
        }
    }

    private suspend fun retryCurrent() {
        if (currentState.currentEntry == null) return
        retryEntryId = null
        suppressEnded = true
        player.stop()
        loadCurrent(autoPlay = true)
    }

    private suspend fun loadCurrent(autoPlay: Boolean) {
        val entry = currentState.currentEntry ?: return
        val account = authenticatedAccount()
        if (account == null || !snapshotBelongsTo(currentState.toSnapshot(account), account)) {
            currentState = currentState.copy(isLoading = false, error = "Sign in to play music")
            AudioServiceBridge.publish(currentState)
            return
        }
        loadingCurrent = true
        lastAutoplay = autoPlay
        httpFactory.setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${account.token}"))
        currentState = currentState.copy(isLoading = true, error = null)
        AudioServiceBridge.publish(currentState)
        try {
            val data =
                repository.playback(
                    account,
                    entry.track.id,
                    PlaybackOptions(
                        engine = PlayerEngine.MEDIA3,
                        startPositionSeconds = currentState.positionSeconds.toDouble(),
                    ),
                )
            val candidateUrl =
                data.url ?: data.source.url ?: error("Server did not return an audio URL")
            val url = resolveSameOriginUrl(account.serverUrl, candidateUrl)
            val normalizedSource = normalizeAudioSource(url, data.mimeType, data.mode)
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
            // Keep ExoPlayer at unity gain and let the device media stream
            // volume control the audible level.
            player.volume = 1f
            player.setMediaSource(buildAudioMediaSource(dataSourceFactory, mediaItem, normalizedSource))
            player.prepare()
            retryEntryId = null
            suppressEnded = false
            val position = (currentState.positionSeconds * 1_000L).coerceAtLeast(0L)
            if (position > 0) player.seekTo(position)
            recordPlayStart(account, entry)
            currentState =
                currentState.copy(
                    isLoading = false,
                    error = null,
                    durationSeconds =
                        (data.durationSeconds
                                ?: data.source.durationSeconds
                                ?: entry.track.durationSeconds)
                            ?.toLong()
                            ?.coerceAtLeast(0L) ?: 0L,
                )
            AudioServiceBridge.publish(currentState)
            if (autoPlay) player.play()
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            if ((error as? CatalogException)?.statusCode == 401)
                repository.clearSessionIfCurrent(account)
            currentState =
                currentState.copy(
                    isLoading = false,
                    error = error.message ?: "Audio could not be played",
                )
            AudioServiceBridge.publish(currentState)
            player.pause()
        } finally {
            loadingCurrent = false
        }
    }

    private suspend fun recordPlayStart(account: AuthSession, oldEntry: AudioQueueEntry) {
        val entry = currentState.currentEntry?.takeIf { it.entryId == oldEntry.entryId } ?: return
        // Reuse a persisted idempotency key after a retry or process recreation.
        // The server treats repeated requests with that key as one play-start.
        val instanceId = entry.playbackInstanceId ?: UUID.randomUUID().toString()
        if (entry.playbackInstanceId == null) {
            currentState =
                currentState.copy(
                    queue =
                        currentState.queue.map { value ->
                            if (value.entryId == entry.entryId)
                                value.copy(playbackInstanceId = instanceId)
                            else value
                        }
                )
            persistSnapshot()
        }
        runCatching { repository.recordAudioPlayStart(account, entry.track.id, instanceId) }
            .onFailure {
                if ((it as? CatalogException)?.statusCode == 401)
                    repository.clearSessionIfCurrent(account)
            }
    }

    private suspend fun advance(force: Boolean) {
        val queue = currentState.queue
        if (queue.isEmpty()) return
        val nextIndex =
            nextQueueIndex(queue.size, currentState.currentIndex, currentState.repeatMode, force)
        if (
            nextIndex == currentState.currentIndex &&
                currentState.repeatMode == AudioRepeatMode.Track &&
                force
        ) {
            currentState = currentState.copy(positionSeconds = 0L)
            loadCurrent(autoPlay = true)
            return
        }
        if (nextIndex == null) {
            player.pause()
            player.seekTo(0L)
            currentState = currentState.copy(isPlaying = false, positionSeconds = 0L)
            publishPlayerState()
            reportProgress()
            return
        }
        reportProgress()
        currentState =
            currentState.copy(currentIndex = nextIndex, positionSeconds = 0L, error = null)
        persistSnapshot()
        suppressEnded = true
        player.stop()
        loadCurrent(autoPlay = true)
    }

    private suspend fun previous() {
        if (player.currentPosition > 5_000L) {
            player.seekTo(0L)
            return
        }
        val previous = previousQueueIndex(currentState.queue.size, currentState.currentIndex)
        if (previous == null) {
            player.seekTo(0L)
            return
        }
        reportProgress()
        currentState =
            currentState.copy(currentIndex = previous, positionSeconds = 0L, error = null)
        persistSnapshot()
        suppressEnded = true
        player.stop()
        loadCurrent(autoPlay = true)
    }

    private suspend fun toggleShuffle() {
        val value = !currentState.shuffle
        sessionStore.saveAudioShuffle(value)
        currentState =
            if (value) shuffleQueueKeepingCurrent(currentState)
            else currentState.copy(shuffle = false)
        publishPlayerState()
        persistSnapshot()
    }

    private suspend fun toggleRepeat() {
        val value = currentState.repeatMode.next()
        sessionStore.saveAudioRepeatMode(value)
        currentState = currentState.copy(repeatMode = value)
        publishPlayerState()
        persistSnapshot()
    }

    private suspend fun setVolume() {
        // Retain the bridge action for compatibility with older clients, but
        // never apply software gain. Volume belongs to Android's media stream.
        player.volume = 1f
        currentState = currentState.copy(volume = 1f, muted = false)
        publishPlayerState()
    }

    private suspend fun toggleMute() {
        // There is no app-level mute anymore; use the phone's volume controls.
        player.volume = 1f
        currentState = currentState.copy(volume = 1f, muted = false)
        publishPlayerState()
    }

    private suspend fun seekTo(positionSeconds: Long) {
        player.seekTo(positionSeconds.coerceAtLeast(0L) * 1_000L)
        currentState = currentState.copy(positionSeconds = positionSeconds.coerceAtLeast(0L))
        publishPlayerState()
        persistSnapshot()
    }

    private suspend fun removeQueue(entryId: String?) {
        if (entryId.isNullOrBlank()) return
        val removedIndex = currentState.queue.indexOfFirst { it.entryId == entryId }
        if (removedIndex < 0) return
        val removal = removeQueueEntry(currentState, entryId) ?: return
        val wasCurrent = removal.removedCurrent
        val wasPlaying = player.isPlaying
        if (removal.entries.isEmpty()) return clearQueue()
        currentState =
            currentState.copy(queue = removal.entries, currentIndex = removal.currentIndex)
        if (wasCurrent) {
            suppressEnded = true
            player.stop()
            loadCurrent(autoPlay = wasPlaying)
        }
        publishPlayerState()
        persistSnapshot()
    }

    private suspend fun reorderQueue(from: Int, to: Int) {
        currentState = reorderQueue(currentState, from, to) ?: return
        publishPlayerState()
        persistSnapshot()
    }

    private suspend fun clearQueue() {
        reportProgress()
        suppressEnded = true
        player.stop()
        currentState = AudioPlayerState()
        AudioServiceBridge.publish(currentState)
        val account = sessionStore.session.first()
        if (account != null) sessionStore.clearAudioQueueSnapshot(account.serverUrl, account.userId)
    }

    private suspend fun pauseForVideo() {
        if (player.isPlaying) reportProgress()
        player.pause()
        currentState = currentState.copy(isPlaying = false)
        publishPlayerState()
        persistSnapshot()
    }

    private suspend fun updateFavorite(itemId: String?, favorite: Boolean) {
        if (itemId.isNullOrBlank()) return
        val updated =
            currentState.queue.map { entry ->
                if (entry.track.id == itemId)
                    entry.copy(track = entry.track.copy(favorite = favorite))
                else entry
            }
        if (updated == currentState.queue) return
        currentState = currentState.copy(queue = updated)
        publishPlayerState()
        persistSnapshot()
    }

    private suspend fun reportProgress(): Unit = progressMutex.withLock {
        val entry = currentState.currentEntry ?: return
        val account = sessionStore.session.first() ?: return
        if (!sessionStore.watchHistoryEnabled.first()) return
        runCatching {
                repository.reportPlayback(
                    account,
                    entry.track.id,
                    player.currentPosition.coerceAtLeast(0L) / 1_000.0,
                    !player.isPlaying,
                    null,
                    currentState.durationSeconds.toDouble().takeIf { it > 0 },
                )
            }
            .onFailure {
                if ((it as? CatalogException)?.statusCode == 401)
                    serviceScope.launch { repository.clearSessionIfCurrent(account) }
            }
    }

    private suspend fun persistSnapshot() {
        persistJob?.cancel()
        persistJob = serviceScope.launch {
            delay(PERSIST_DEBOUNCE_MILLIS)
            val account = sessionStore.session.first() ?: return@launch
            sessionStore.saveAudioQueueSnapshot(currentState.toSnapshot(account))
        }
    }

    private suspend fun persistSnapshotNow() {
        val account = sessionStore.session.first() ?: return
        if (currentState.queue.isEmpty()) {
            sessionStore.clearAudioQueueSnapshot(account.serverUrl, account.userId)
        } else {
            sessionStore.saveAudioQueueSnapshot(currentState.toSnapshot(account))
        }
    }

    private fun publishPlayerState() {
        val duration =
            player.duration.takeIf { it != C.TIME_UNSET && it >= 0 }?.div(1_000L)
                ?: currentState.durationSeconds
        val position = player.currentPosition.coerceAtLeast(0L).div(1_000L)
        currentState =
            currentState.copy(
                positionSeconds = position,
                durationSeconds = duration,
                isPlaying = player.isPlaying,
                isLoading = loadingCurrent,
            )
        AudioServiceBridge.publish(currentState)
    }

    private val playerListener =
        object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publishPlayerState()
                if (!isPlaying)
                    serviceScope.launch {
                        reportProgress()
                        persistSnapshot()
                    }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                publishPlayerState()
                if (playbackState == Player.STATE_ENDED && !loadingCurrent && !suppressEnded) {
                    serviceScope.launch { advance(force = true) }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val mediaId = mediaItem?.mediaId.orEmpty()
                if (mediaId.startsWith("zenstream:track:")) {
                    serviceScope.launch { adoptAutoTrack(mediaId.removePrefix("zenstream:track:")) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val entryId = currentState.currentEntry?.entryId
                if (entryId != null && retryEntryId != entryId) {
                    retryEntryId = entryId
                    serviceScope.launch {
                        player.stop()
                        loadCurrent(autoPlay = lastAutoplay)
                    }
                } else {
                    currentState =
                        currentState.copy(
                            isLoading = false,
                            error = error.message ?: "Audio decoder error",
                        )
                    AudioServiceBridge.publish(currentState)
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

    private fun parseSnapshot(encoded: String): AudioQueueSnapshot? =
        runCatching { audioQueueSnapshotFromJson(JSONObject(encoded)) }.getOrNull()

    private suspend fun adoptAutoTrack(trackId: String) {
        val account = sessionStore.session.first() ?: return
        loadAutoCatalog()
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
        publishPlayerState()
        currentState.currentEntry?.let { recordPlayStart(account, it) }
        persistSnapshot()
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
        runBlocking {
            withTimeoutOrNull(SHUTDOWN_FLUSH_TIMEOUT_MILLIS) {
                reportProgress()
                persistSnapshotNow()
            }
        }
        positionJob?.cancel()
        progressJob?.cancel()
        librarySession.release()
        player.removeListener(playerListener)
        player.release()
        serviceScope.coroutineContext.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * Restore is issued when the app starts, before there is necessarily a current
     * MediaItem for Media3's own notification provider. Starting foreground work
     * immediately prevents Android's foreground-service timeout from turning a
     * normal cold start into an ANR. Media3 updates its session notification once
     * the player has queue metadata.
     */
    private fun startServiceForeground() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                AUDIO_NOTIFICATION_CHANNEL,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Audio playback controls"
                setShowBadge(false)
            },
        )
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(this, AUDIO_NOTIFICATION_CHANNEL)
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
            AUDIO_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int =
            when (playerCommand) {
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                    serviceScope.launch { advance(force = false) }
                    Player.COMMAND_INVALID
                }
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                    serviceScope.launch { previous() }
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
        val trackId = request.mediaId
            .takeIf { it.startsWith("zenstream:track:") }
            ?.removePrefix("zenstream:track:")
            ?.takeIf(String::isNotBlank)
            ?: error("Android Auto requested an unsupported media id")
        val track = catalogTracks.values.asSequence().flatten().firstOrNull { it.id == trackId }
            ?: error("Music track is no longer available")
        val account = authenticatedAccount() ?: error("Sign in to play music")
        httpFactory.setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${account.token}"))
        val data =
            repository.playback(account, track.id, PlaybackOptions(engine = PlayerEngine.MEDIA3))
        val candidateUrl =
            data.url ?: data.source.url ?: error("Server did not return an audio URL")
        val url = resolveSameOriginUrl(account.serverUrl, candidateUrl)
        val normalizedSource = normalizeAudioSource(url, data.mimeType, data.mode)
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
        val path = item?.imageTags?.get("Primary")?.takeIf { it.startsWith("/api/") } ?: return null
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
            shuffle = shuffle,
            repeatMode = repeatMode,
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
        )

    companion object {
        private const val PROGRESS_INTERVAL_MILLIS = 10_000L
        private const val PERSIST_DEBOUNCE_MILLIS = 250L
        private const val SHUTDOWN_FLUSH_TIMEOUT_MILLIS = 2_000L
        private const val AUTO_PAGE_SIZE = 100
        private const val AUTO_MAX_ALBUMS = 1_000
        private const val AUTO_ARTIST_LIMIT = 100
        private const val AUDIO_NOTIFICATION_CHANNEL = "audio_playback"
        private const val AUDIO_NOTIFICATION_ID = 21_847

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

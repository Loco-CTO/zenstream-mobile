package com.zenstream.zenstreammobile.data

import android.content.ContentResolver
import android.net.Uri
import com.zenstream.zenstreammobile.model.AudioLyrics
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.BazarrSearchResult
import com.zenstream.zenstreammobile.model.BazarrStatus
import com.zenstream.zenstreammobile.model.CalendarResponse
import com.zenstream.zenstreammobile.model.DerivedHomeData
import com.zenstream.zenstreammobile.model.FavoriteSort
import com.zenstream.zenstreammobile.model.HomeData
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibraryData
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MpvVideoOutput
import com.zenstream.zenstreammobile.model.MpvVideoProfile
import com.zenstream.zenstreammobile.model.MpvVideoScaler
import com.zenstream.zenstreammobile.model.MusicAlbumData
import com.zenstream.zenstreammobile.model.MusicArtistData
import com.zenstream.zenstreammobile.model.PagedFavorites
import com.zenstream.zenstreammobile.model.PagedLibrary
import com.zenstream.zenstreammobile.model.PagedSearch
import com.zenstream.zenstreammobile.model.PlaybackData
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlaybackTimeDisplayMode
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.PlaylistData
import com.zenstream.zenstreammobile.model.PlaylistSummary
import com.zenstream.zenstreammobile.model.SearchFilter
import com.zenstream.zenstreammobile.model.SubtitleStyle
import com.zenstream.zenstreammobile.model.ViewerCommandAck
import com.zenstream.zenstreammobile.model.ViewerEnd
import com.zenstream.zenstreammobile.model.ViewerHeartbeat
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

private data class AudioLyricsCacheKey(
    val serverUrl: String,
    val userId: String,
    val itemId: String,
)

private sealed interface AudioLyricsLookup {
    data class Cached(val value: AudioLyrics) : AudioLyricsLookup

    data class InFlight(val request: Deferred<AudioLyrics?>) : AudioLyricsLookup
}

/** Shares one lyrics request between the playback service and the foreground UI. */
private object AudioLyricsCache {
    private const val MAX_ENTRIES = 48

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val values =
        LinkedHashMap<AudioLyricsCacheKey, AudioLyrics>(
            MAX_ENTRIES,
            .75f,
            true,
        )
    private val inFlight = mutableMapOf<AudioLyricsCacheKey, Deferred<AudioLyrics?>>()

    suspend fun getOrLoad(
        key: AudioLyricsCacheKey,
        loader: suspend () -> AudioLyrics?,
    ): AudioLyrics? {
        val lookup = mutex.withLock {
            values[key]?.let { AudioLyricsLookup.Cached(it) }
                ?: AudioLyricsLookup.InFlight(inFlight.getOrPut(key) { scope.async { loader() } })
        }
        return when (lookup) {
            is AudioLyricsLookup.Cached -> lookup.value
            is AudioLyricsLookup.InFlight ->
                try {
                    lookup.request.await()?.also { value ->
                        mutex.withLock {
                            values[key] = value
                            while (values.size > MAX_ENTRIES) {
                                val iterator = values.entries.iterator()
                                iterator.next()
                                iterator.remove()
                            }
                        }
                    }
                } finally {
                    mutex.withLock {
                        if (inFlight[key] === lookup.request) inFlight.remove(key)
                    }
                }
        }
    }

    suspend fun clear() {
        val requests = mutex.withLock {
            values.clear()
            val pending = inFlight.values.toList()
            inFlight.clear()
            pending
        }
        requests.forEach { it.cancel() }
    }
}

private val accountRefreshMutex = Mutex()
private const val REFRESH_EXPIRY_SKEW_MILLIS = 5_000L
private const val MAX_CACHED_REFRESH_RECOVERIES = 2

interface CatalogRefreshSource {
    val catalogRefreshRevision: Flow<Long>
        get() = kotlinx.coroutines.flow.emptyFlow()

    suspend fun clearSession()

    suspend fun clearSessionIfCurrent(session: AuthSession) {
        clearSession()
    }

    suspend fun setFollowing(session: AuthSession, itemId: String, following: Boolean) {}
}

interface HomeDataSource : CatalogRefreshSource {
    override suspend fun clearSession()

    suspend fun homeFeatured(session: AuthSession): List<MediaItem>

    suspend fun homeContinueWatching(session: AuthSession): List<MediaItem>

    suspend fun homeNextUp(session: AuthSession): List<MediaItem>

    suspend fun homeRecommendations(session: AuthSession): List<MediaItem>

    suspend fun homeDerived(session: AuthSession): DerivedHomeData

    suspend fun homeLibraries(session: AuthSession): List<Library>

    suspend fun homeLibraryData(session: AuthSession, library: Library): LibraryData
}

interface LibraryDataSource : CatalogRefreshSource {
    override suspend fun clearSession()

    suspend fun libraries(session: AuthSession): List<Library>

    suspend fun libraryPage(
        session: AuthSession,
        library: Library,
        startIndex: Int,
        limit: Int,
        sort: LibrarySort,
    ): PagedLibrary

    suspend fun cachedLibrarySort(userId: String, libraryId: String): LibrarySort?

    suspend fun saveLibrarySort(userId: String, libraryId: String, sort: LibrarySort)
}

interface SearchDataSource : CatalogRefreshSource {
    override suspend fun clearSession()

    suspend fun search(session: AuthSession, query: String, page: Int): PagedSearch

    suspend fun search(
        session: AuthSession,
        query: String,
        page: Int,
        filter: SearchFilter,
    ): PagedSearch = search(session, query, page)
}

interface PlaylistDataSource {
    suspend fun watchlist(session: AuthSession): List<MediaItem> = unsupportedPlaylistOperation()

    suspend fun playlists(
        session: AuthSession,
        membershipSourceId: String? = null,
    ): List<PlaylistSummary> = unsupportedPlaylistOperation()

    suspend fun playlist(
        session: AuthSession,
        playlistId: String,
        page: Int? = null,
    ): PlaylistData = unsupportedPlaylistOperation()

    suspend fun sharedPlaylist(
        session: AuthSession,
        shareToken: String,
        page: Int? = null,
    ): PlaylistData = unsupportedPlaylistOperation()

    suspend fun createPlaylist(
        session: AuthSession,
        name: String,
        description: String?,
        isPrivate: Boolean,
        entityId: String? = null,
    ): PlaylistData = unsupportedPlaylistOperation()

    suspend fun updatePlaylist(
        session: AuthSession,
        playlistId: String,
        name: String,
        description: String?,
        isPrivate: Boolean,
    ): PlaylistData = unsupportedPlaylistOperation()

    suspend fun deletePlaylist(session: AuthSession, playlistId: String) =
        unsupportedPlaylistOperation<Unit>()

    suspend fun addPlaylistItems(
        session: AuthSession,
        playlistId: String,
        entityIds: List<String>,
    ): PlaylistData = unsupportedPlaylistOperation()

    suspend fun removePlaylistEntry(
        session: AuthSession,
        playlistId: String,
        entryId: String,
    ): PlaylistData = unsupportedPlaylistOperation()

    suspend fun removePlaylistSource(
        session: AuthSession,
        playlistId: String,
        sourceId: String,
    ): PlaylistData = unsupportedPlaylistOperation()

    suspend fun movePlaylistEntry(
        session: AuthSession,
        playlistId: String,
        entryId: String,
        beforeEntryId: String? = null,
        afterEntryId: String? = null,
    ): PlaylistData = unsupportedPlaylistOperation()

    suspend fun reorderPlaylist(
        session: AuthSession,
        playlistId: String,
        entryIds: List<String>,
    ): PlaylistData = unsupportedPlaylistOperation()
}

private suspend fun <T> unsupportedPlaylistOperation(): T =
    throw UnsupportedOperationException("Playlists are not supported by this data source")

interface FavoritesDataSource : CatalogRefreshSource, PlaylistDataSource {
    override suspend fun clearSession()

    suspend fun favoritesPage(
        session: AuthSession,
        startIndex: Int,
        limit: Int,
        sort: FavoriteSort,
    ): PagedFavorites

    suspend fun cachedFavoriteSort(userId: String): FavoriteSort?

    suspend fun saveFavoriteSort(userId: String, sort: FavoriteSort)

    suspend fun setFavorite(session: AuthSession, itemId: String, favorite: Boolean) =
        unsupportedPlaylistOperation<Unit>()
}

interface MusicDataSource : CatalogRefreshSource, PlaylistDataSource {
    override suspend fun clearSession()

    suspend fun musicAlbum(session: AuthSession, albumId: String): MusicAlbumData

    suspend fun musicArtist(session: AuthSession, artistId: String): MusicArtistData

    suspend fun musicArtistTracks(session: AuthSession, artistId: String): List<MediaItem>

    suspend fun audioLyrics(session: AuthSession, itemId: String): AudioLyrics?

    suspend fun recordAudioPlayStart(
        session: AuthSession,
        itemId: String,
        playbackInstanceId: String,
    )

    suspend fun setFavorite(session: AuthSession, itemId: String, favorite: Boolean)
}

interface CalendarDataSource : CatalogRefreshSource {
    override suspend fun clearSession()

    suspend fun calendar(
        session: AuthSession,
        start: Instant,
        end: Instant,
    ): CalendarResponse

    suspend fun setCalendarFollowing(
        session: AuthSession,
        eventId: String,
        following: Boolean,
    ): Boolean
}

interface SettingsDataSource {
    val interfaceLocaleMode: Flow<InterfaceLocaleMode>
    val playerEngine: Flow<PlayerEngine>
    val mpvVideoOutput: Flow<MpvVideoOutput>
    val mpvVideoProfile: Flow<MpvVideoProfile>
    val mpvVideoScaler: Flow<MpvVideoScaler>
    val showDebugIcon: Flow<Boolean>
    val autoplayNextEpisode: Flow<Boolean>
    val automaticPictureInPicture: Flow<Boolean>
    val checkForUpdatesOnStartup: Flow<Boolean>
    val watchHistoryEnabled: Flow<Boolean>

    suspend fun loadMetadataPreference(): MetadataPreference

    suspend fun saveMetadataPreference(language: String?): MetadataPreference

    suspend fun saveInterfaceLocaleMode(mode: InterfaceLocaleMode): InterfaceLocalePreference

    suspend fun savePlayerEngine(engine: PlayerEngine)

    suspend fun saveMpvVideoOutput(output: MpvVideoOutput)

    suspend fun saveMpvVideoProfile(profile: MpvVideoProfile)

    suspend fun saveMpvVideoScaler(scaler: MpvVideoScaler)

    suspend fun saveShowDebugIcon(enabled: Boolean)

    suspend fun saveAutoplayNextEpisode(enabled: Boolean)

    suspend fun saveAutomaticPictureInPicture(enabled: Boolean)

    suspend fun saveCheckForUpdatesOnStartup(enabled: Boolean)

    suspend fun loadWatchHistoryPreference(): Boolean

    suspend fun saveWatchHistoryPreference(enabled: Boolean): Boolean

    suspend fun clearWatchHistory()

    suspend fun loadSubtitleStyle(): SubtitleStyle

    suspend fun saveSubtitleStyle(style: SubtitleStyle): SubtitleStyle

    suspend fun loadPlaybackPreference(): PlaybackPreference

    suspend fun savePlaybackPreference(
        audioLanguage: String?,
        subtitleLanguage: String?,
    ): PlaybackPreference
}

data class InterfaceLocalePreference(
    val mode: InterfaceLocaleMode,
    val locale: String,
    val metadataPreference: MetadataPreference?,
)

class CatalogRepository(
    private val api: CatalogApi,
    private val sessionStore: SessionStore,
    private val orchestratorApi: OrchestratorApi = OrchestratorApi(),
) :
    HomeDataSource,
    LibraryDataSource,
    SearchDataSource,
    FavoritesDataSource,
    MusicDataSource,
    CalendarDataSource,
    SettingsDataSource {

    suspend fun revokeSession(session: AuthSession) {
        requestAuth.authenticatedCatalogRequest(session) { current -> api.logout(current) }
    }

    private val homeMutex = Mutex()
    private val interfaceLocaleMutex = Mutex()
    private val playbackPreferenceMutex = Mutex()
    private val sessionRestoreRevision = MutableStateFlow(0L)
    private val _authRefreshState = MutableStateFlow<AuthRefreshState>(AuthRefreshState.Idle)
    private var homeCache: Pair<Long, HomeData>? = null
    private var playbackPreferenceCache: Pair<Long, PlaybackPreference>? = null
    private val _catalogRefreshRevision = MutableStateFlow(0L)
    override val catalogRefreshRevision: StateFlow<Long> = _catalogRefreshRevision
    val serverUrl: Flow<String?> = sessionStore.serverUrl
    val orchestratorUrl: Flow<String?> = sessionStore.orchestratorUrl
    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionState: Flow<StoredSessionState> = sessionRestoreRevision.flatMapLatest {
        sessionStore.sessionState
    }
    val session: Flow<AuthSession?> =
        sessionState
            .filterNot { it is StoredSessionState.Loading }
            .map { state -> (state as? StoredSessionState.Loaded)?.session }
    val locale: Flow<String> = sessionStore.locale
    override val interfaceLocaleMode: Flow<InterfaceLocaleMode> = sessionStore.interfaceLocaleMode
    val metadataLanguage: Flow<String> = sessionStore.metadataLanguage
    override val playerEngine: Flow<PlayerEngine> = sessionStore.playerEngine
    override val mpvVideoOutput: Flow<MpvVideoOutput> = sessionStore.mpvVideoOutput
    override val mpvVideoProfile: Flow<MpvVideoProfile> = sessionStore.mpvVideoProfile
    override val mpvVideoScaler: Flow<MpvVideoScaler> = sessionStore.mpvVideoScaler
    val playbackTimeDisplayMode: Flow<PlaybackTimeDisplayMode> =
        sessionStore.playbackTimeDisplayMode
    override val showDebugIcon: Flow<Boolean> = sessionStore.showDebugIcon
    override val autoplayNextEpisode: Flow<Boolean> = sessionStore.autoplayNextEpisode
    override val automaticPictureInPicture: Flow<Boolean> = sessionStore.automaticPictureInPicture
    override val checkForUpdatesOnStartup: Flow<Boolean> = sessionStore.checkForUpdatesOnStartup
    override val watchHistoryEnabled: Flow<Boolean> = sessionStore.watchHistoryEnabled

    private val requestAuth = RequestAuth(sessionStore, api, sessionState, session)
    val authRefreshState: StateFlow<AuthRefreshState> = requestAuth.authRefreshState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        scope.launch {
            requestAuth.sessionClearedEvents.collect { clearedSession ->
                clearSessionLocalState()
            }
        }
    }

    fun retrySessionRestore() {
        sessionRestoreRevision.update { it + 1L }
    }

    suspend fun saveServerUrl(value: String) = sessionStore.saveServerUrl(normalizeServerUrl(value))

    suspend fun configureOrchestrator(value: String) {
        val orchestrator = normalizeServerUrl(value)
        // Keep the user's server choice before the network request. If the
        // config endpoint is temporarily unavailable, the next launch can
        // keep the configured address instead of presenting a blank form.
        sessionStore.saveOrchestratorUrl(orchestrator)
        orchestratorApi.fetchConfig(orchestrator)
        sessionStore.saveServerConfig(orchestrator)
    }

    suspend fun fetchPublicWebUrl(session: AuthSession): String? =
        orchestratorApi.fetchPublicWebUrl(session.serverUrl)

    suspend fun authenticate(username: String, password: String): AuthSession {
        val server = sessionStore.currentServerUrl() ?: error("Server URL is not configured")
        return accountRefreshMutex.withLock {
            api.authenticate(server, username, password, sessionStore.deviceId()).also {
                sessionStore.saveSession(it)
                _authRefreshState.value = AuthRefreshState.Idle
            }
        }
    }

    suspend fun refreshCurrentAccount(): AuthSession {
        val current = session.first() ?: error("Authentication required")
        AuthLifecycleLog.event("startup_account_refresh_started", current)
        _authRefreshState.value = AuthRefreshState.Refreshing(current)
        return try {
            val refreshed =
                requestAuth.authenticatedCatalogRequest(current) { value -> api.refreshAccount(value) }
            val saved =
                sessionStore.saveSessionIfCurrent(current, refreshed) ||
                    sessionStore.saveSessionIfCurrent(refreshed, refreshed)
            if (!saved) {
                _authRefreshState.value = AuthRefreshState.Idle
                throw IllegalStateException("Authentication session changed during restoration")
            }
            _authRefreshState.value = AuthRefreshState.Idle
            AuthLifecycleLog.event("startup_account_refresh_completed", refreshed)
            refreshed
        } catch (error: CancellationException) {
            _authRefreshState.value =
                AuthRefreshState.TemporarilyUnavailable(current, "RefreshCancelled")
            AuthLifecycleLog.event(
                "startup_account_refresh_cancelled",
                current,
                errorType = error::class.java.simpleName,
            )
            throw error
        } catch (error: Exception) {
            if (_authRefreshState.value !is AuthRefreshState.Rejected) {
                val currentSession = session.first()
                if (currentSession != null) {
                    _authRefreshState.value =
                        AuthRefreshState.TemporarilyUnavailable(
                            currentSession,
                            error::class.java.simpleName,
                        )
                }
            }
            AuthLifecycleLog.event(
                "startup_account_refresh_failed",
                session.first(),
                errorType = error::class.java.simpleName,
            )
            throw error
        }
    }

    suspend fun uploadAvatar(
        session: AuthSession,
        resolver: ContentResolver,
        uri: Uri,
        crop: AvatarCrop,
    ): AuthSession {
        val version =
            requestAuth.authenticatedCatalogRequest(session) { current ->
                api.uploadAvatar(current, resolver, uri, crop)
            }
        return updateAvatarVersionIfCurrent(session, version)
    }

    suspend fun removeAvatar(session: AuthSession): AuthSession {
        requestAuth.authenticatedCatalogRequest(session) { current -> api.deleteAvatar(current) }
        return updateAvatarVersionIfCurrent(session, null)
    }

    suspend fun changePassword(
        session: AuthSession,
        currentPassword: String,
        newPassword: String,
        confirmNewPassword: String,
    ) {
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.changePassword(current, currentPassword, newPassword, confirmNewPassword)
        }
    }

    suspend fun syncInterfaceLocale(current: AuthSession) = interfaceLocaleMutex.withLock {
        val mode = interfaceLocaleMode.first()
        val resolvedLocale = sessionStore.resolveInterfaceLocale(mode)
        val remoteLocale =
            requestAuth.authenticatedOrchestratorRequest(current) { value ->
                orchestratorApi.fetchLocale(value.serverUrl, value.token)
            }
        var localeChanged = false
        if (remoteLocale != resolvedLocale) {
            val savedLocale =
                requestAuth.authenticatedOrchestratorRequest(current) { value ->
                    orchestratorApi.setLocale(value.serverUrl, value.token, resolvedLocale)
                }
            check(savedLocale == resolvedLocale) { "Orchestrator returned a different locale" }
            localeChanged = true
        }

        val previousMetadataLanguage = metadataLanguage.first()
        val metadataPreference = loadMetadataPreferenceOrNull(current)
        if (metadataPreference != null) {
            sessionStore.saveMetadataLanguage(metadataPreference.effectiveLanguage)
        }
        if (
            localeChanged ||
                metadataPreference?.effectiveLanguage != null &&
                    metadataPreference.effectiveLanguage != previousMetadataLanguage
        ) {
            invalidateCatalogMetadata()
        }
    }

    override suspend fun saveInterfaceLocaleMode(
        mode: InterfaceLocaleMode
    ): InterfaceLocalePreference = interfaceLocaleMutex.withLock {
        val current = session.first() ?: error("Authentication required")
        val resolvedLocale = sessionStore.resolveInterfaceLocale(mode)
        val savedLocale =
            requestAuth.authenticatedOrchestratorRequest(current) { value ->
                orchestratorApi.setLocale(value.serverUrl, value.token, resolvedLocale)
            }
        check(savedLocale == resolvedLocale) { "Orchestrator returned a different locale" }
        sessionStore.saveInterfaceLocaleMode(mode)

        val metadataPreference = loadMetadataPreferenceOrNull(current)
        if (metadataPreference != null) {
            sessionStore.saveMetadataLanguage(metadataPreference.effectiveLanguage)
        }
        invalidateCatalogMetadata()
        InterfaceLocalePreference(mode, savedLocale, metadataPreference)
    }

    private suspend fun loadMetadataPreferenceOrNull(current: AuthSession): MetadataPreference? =
        try {
            requestAuth.authenticatedOrchestratorRequest(current) { value ->
                orchestratorApi.fetchMetadataPreference(value.serverUrl, value.token)
            }
        } catch (error: OrchestratorException) {
            if (error.statusCode == 401) throw error
            null
        }

    private suspend fun updateAvatarVersionIfCurrent(
        expected: AuthSession,
        avatarVersion: String?,
    ): AuthSession {
        val latest = requestAuth.currentSessionForRequest(expected)
        val updated = latest.copy(avatarVersion = avatarVersion)
        return if (sessionStore.saveSessionIfCurrent(latest, updated)) {
            updated
        } else {
            expected.copy(avatarVersion = avatarVersion)
        }
    }

    override suspend fun loadMetadataPreference(): MetadataPreference {
        val current = session.first() ?: error("Authentication required")
        return requestAuth.authenticatedOrchestratorRequest(current) { value ->
                orchestratorApi.fetchMetadataPreference(value.serverUrl, value.token)
            }
            .also { sessionStore.saveMetadataLanguage(it.effectiveLanguage) }
    }

    override suspend fun saveMetadataPreference(language: String?): MetadataPreference {
        val current = session.first() ?: error("Authentication required")
        return requestAuth.authenticatedOrchestratorRequest(current) { value ->
                orchestratorApi.setMetadataPreference(value.serverUrl, value.token, language)
            }
            .also {
                sessionStore.saveMetadataLanguage(it.effectiveLanguage)
                invalidateCatalogMetadata()
            }
    }

    override suspend fun clearSession() = clearSession("explicit")

    suspend fun clearSession(reason: String) {
        accountRefreshMutex.withLock {
            clearSessionLocalState()
            sessionStore.clearSession(reason)
            _authRefreshState.value = AuthRefreshState.Idle
        }
    }

    override suspend fun clearSessionIfCurrent(session: AuthSession) {
        accountRefreshMutex.withLock {
            val rejected = _authRefreshState.value as? AuthRefreshState.Rejected
            if (rejected?.session?.token != session.token) return@withLock
            if (sessionStore.clearSessionIfCurrent(rejected.session, "refresh_rejected")) {
                clearSessionLocalState()
            }
        }
    }

    suspend fun clearAll() {
        accountRefreshMutex.withLock {
            clearSessionLocalState()
            sessionStore.clearAll()
            _authRefreshState.value = AuthRefreshState.Idle
        }
    }

    private suspend fun clearSessionLocalState() {
        SyncplaySession.clear()
        homeMutex.withLock { homeCache = null }
        playbackPreferenceMutex.withLock { playbackPreferenceCache = null }
        AudioLyricsCache.clear()
    }

    override suspend fun homeFeatured(session: AuthSession) =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.fetchHomeFeatured(current) }

    override suspend fun homeContinueWatching(session: AuthSession) =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.fetchHomeContinueWatching(current) }

    override suspend fun homeNextUp(session: AuthSession) =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.fetchHomeNextUp(current) }

    override suspend fun homeRecommendations(session: AuthSession) =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.fetchHomeRecommendations(current) }

    override suspend fun homeDerived(session: AuthSession) =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.fetchHomeDerived(current) }

    override suspend fun homeLibraries(session: AuthSession) =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.getLibraries(current, CatalogApi.HOME_REQUEST_TIMEOUT_MILLIS)
        }

    override suspend fun homeLibraryData(session: AuthSession, library: Library) =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.fetchHomeLibraryData(current, library, CatalogApi.HOME_REQUEST_TIMEOUT_MILLIS)
        }

    override suspend fun libraries(session: AuthSession) =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.getLibraries(current) }

    suspend fun library(
        session: AuthSession,
        library: Library,
    ) = requestAuth.authenticatedCatalogRequest(session) { current -> api.fetchLibraryData(current, library) }

    override suspend fun libraryPage(
        session: AuthSession,
        library: Library,
        startIndex: Int,
        limit: Int,
        sort: LibrarySort,
    ): PagedLibrary =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.fetchLibraryPage(current, library, startIndex, limit, sort)
        }

    override suspend fun search(session: AuthSession, query: String, page: Int) =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.search(current, query, page) }

    override suspend fun search(
        session: AuthSession,
        query: String,
        page: Int,
        filter: SearchFilter,
    ) = requestAuth.authenticatedCatalogRequest(session) { current -> api.search(current, query, page, filter) }

    override suspend fun favoritesPage(
        session: AuthSession,
        startIndex: Int,
        limit: Int,
        sort: FavoriteSort,
    ) =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.fetchFavoritesPage(current, startIndex, limit, sort)
        }

    override suspend fun watchlist(session: AuthSession): List<MediaItem> =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.fetchWatchlist(current) }

    override suspend fun playlists(
        session: AuthSession,
        membershipSourceId: String?,
    ): List<PlaylistSummary> =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.fetchPlaylists(current, membershipSourceId)
        }

    override suspend fun playlist(
        session: AuthSession,
        playlistId: String,
        page: Int?,
    ): PlaylistData =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.fetchPlaylist(current, playlistId, page)
        }

    override suspend fun sharedPlaylist(
        session: AuthSession,
        shareToken: String,
        page: Int?,
    ): PlaylistData =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.fetchSharedPlaylist(current, shareToken, page)
        }

    override suspend fun createPlaylist(
        session: AuthSession,
        name: String,
        description: String?,
        isPrivate: Boolean,
        entityId: String?,
    ): PlaylistData {
        val result =
            requestAuth.authenticatedCatalogRequest(session) { current ->
                api.createPlaylist(current, name, description, isPrivate, entityId)
            }
        invalidateCatalogState()
        return result
    }

    override suspend fun updatePlaylist(
        session: AuthSession,
        playlistId: String,
        name: String,
        description: String?,
        isPrivate: Boolean,
    ): PlaylistData {
        val result =
            requestAuth.authenticatedCatalogRequest(session) { current ->
                api.updatePlaylist(current, playlistId, name, description, isPrivate)
            }
        invalidateCatalogState()
        return result
    }

    override suspend fun deletePlaylist(session: AuthSession, playlistId: String) {
        requestAuth.authenticatedCatalogRequest(session) { current -> api.deletePlaylist(current, playlistId) }
        invalidateCatalogState()
    }

    override suspend fun addPlaylistItems(
        session: AuthSession,
        playlistId: String,
        entityIds: List<String>,
    ): PlaylistData {
        val result =
            requestAuth.authenticatedCatalogRequest(session) { current ->
                api.addPlaylistItems(current, playlistId, entityIds)
            }
        invalidateCatalogState()
        return result
    }

    override suspend fun removePlaylistEntry(
        session: AuthSession,
        playlistId: String,
        entryId: String,
    ): PlaylistData {
        val result =
            requestAuth.authenticatedCatalogRequest(session) { current ->
                api.removePlaylistEntry(current, playlistId, entryId)
            }
        invalidateCatalogState()
        return result
    }

    override suspend fun removePlaylistSource(
        session: AuthSession,
        playlistId: String,
        sourceId: String,
    ): PlaylistData {
        val result =
            requestAuth.authenticatedCatalogRequest(session) { current ->
                api.removePlaylistSource(current, playlistId, sourceId)
            }
        invalidateCatalogState()
        return result
    }

    override suspend fun movePlaylistEntry(
        session: AuthSession,
        playlistId: String,
        entryId: String,
        beforeEntryId: String?,
        afterEntryId: String?,
    ): PlaylistData {
        val result =
            requestAuth.authenticatedCatalogRequest(session) { current ->
                api.movePlaylistEntry(current, playlistId, entryId, beforeEntryId, afterEntryId)
            }
        invalidateCatalogState()
        return result
    }

    override suspend fun reorderPlaylist(
        session: AuthSession,
        playlistId: String,
        entryIds: List<String>,
    ): PlaylistData {
        val result =
            requestAuth.authenticatedCatalogRequest(session) { current ->
                api.reorderPlaylist(current, playlistId, entryIds)
            }
        invalidateCatalogState()
        return result
    }

    override suspend fun musicAlbum(session: AuthSession, albumId: String): MusicAlbumData =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.musicAlbum(current, albumId) }

    override suspend fun musicArtist(session: AuthSession, artistId: String): MusicArtistData =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.musicArtist(current, artistId) }

    override suspend fun musicArtistTracks(
        session: AuthSession,
        artistId: String,
    ): List<MediaItem> =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.musicArtistTracks(current, artistId) }

    override suspend fun audioLyrics(session: AuthSession, itemId: String): AudioLyrics? =
        AudioLyricsCache.getOrLoad(AudioLyricsCacheKey(session.serverUrl, session.userId, itemId)) {
            requestAuth.authenticatedCatalogRequest(session) { current -> api.audioLyrics(current, itemId) }
        }

    suspend fun prefetchAudioLyrics(session: AuthSession, itemId: String) {
        try {
            audioLyrics(session, itemId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Prefetch is best-effort. The foreground lyrics page will retry on demand.
        }
    }

    override suspend fun recordAudioPlayStart(
        session: AuthSession,
        itemId: String,
        playbackInstanceId: String,
    ) {
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.recordAudioPlayStart(current, itemId, playbackInstanceId)
        }
        invalidateCatalogState()
    }

    override suspend fun cachedFavoriteSort(userId: String): FavoriteSort? =
        sessionStore.cachedFavoriteSort(userId)

    override suspend fun saveFavoriteSort(userId: String, sort: FavoriteSort) =
        sessionStore.cacheFavoriteSort(userId, sort)

    override suspend fun cachedLibrarySort(userId: String, libraryId: String): LibrarySort? =
        sessionStore.cachedLibrarySort(userId, libraryId)

    override suspend fun saveLibrarySort(userId: String, libraryId: String, sort: LibrarySort) =
        sessionStore.cacheLibrarySort(userId, libraryId, sort)

    suspend fun detail(session: AuthSession, itemId: String, seasonId: String? = null) =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.detail(current, itemId, seasonId) }

    suspend fun catalogItem(
        session: AuthSession,
        itemId: String,
        requestTimeoutMillis: Long? = null,
    ): MediaItem =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.catalogItem(current, itemId, requestTimeoutMillis)
        }

    override suspend fun setFavorite(session: AuthSession, itemId: String, favorite: Boolean) {
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.setFavorite(current, itemId, favorite)
        }
        invalidateCatalogState()
    }

    suspend fun setPlayed(session: AuthSession, itemId: String, played: Boolean) {
        requestAuth.authenticatedCatalogRequest(session) { current -> api.setPlayed(current, itemId, played) }
        invalidateHomeCache()
    }

    override suspend fun setFollowing(session: AuthSession, itemId: String, following: Boolean) {
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.setFollowing(current, itemId, following)
        }
        invalidateCatalogState()
    }

    override suspend fun calendar(
        session: AuthSession,
        start: Instant,
        end: Instant,
    ): CalendarResponse =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.calendar(current, start, end) }

    override suspend fun setCalendarFollowing(
        session: AuthSession,
        eventId: String,
        following: Boolean,
    ): Boolean {
        val result =
            requestAuth.authenticatedCatalogRequest(session) { current ->
                api.setCalendarFollowing(current, eventId, following)
            }
        invalidateCatalogState()
        return result
    }

    suspend fun notifications(
        session: AuthSession,
        limit: Int = 50,
        cursor: String? = null,
    ) =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.notifications(current, limit, cursor)
        }

    suspend fun notificationSummary(session: AuthSession) =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.notificationSummary(current) }

    suspend fun setNotificationRead(
        session: AuthSession,
        notificationId: String,
        read: Boolean,
    ) =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.setNotificationRead(current, notificationId, read)
        }

    suspend fun deleteNotification(session: AuthSession, notificationId: String) =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.deleteNotification(current, notificationId)
        }

    suspend fun markAllNotificationsRead(session: AuthSession) =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.markAllNotificationsRead(current) }

    suspend fun playback(
        session: AuthSession,
        itemId: String,
        options: PlaybackOptions = PlaybackOptions(),
    ): PlaybackData {
        api.setDeviceId(sessionStore.deviceId())
        return requestAuth.authenticatedCatalogRequest(session) { current ->
            api.playback(current, itemId, options)
        }
    }

    suspend fun playbackSource(session: AuthSession, itemId: String) =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.playbackSource(current, itemId) }

    suspend fun refreshPlaybackAccess(
        session: AuthSession,
        itemId: String,
        sourceId: String,
        playbackSessionId: String? = null,
        playbackAccessMode: String? = null,
        playbackLeaseToken: String? = null,
    ) =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.refreshPlaybackAccess(
                current,
                itemId,
                sourceId,
                playbackSessionId,
                playbackAccessMode,
                playbackLeaseToken,
            )
        }

    suspend fun bazarrStatus(session: AuthSession, itemId: String, sourceId: String): BazarrStatus =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.bazarrStatus(current, itemId, sourceId)
        }

    suspend fun searchBazarrSubtitles(
        session: AuthSession,
        itemId: String,
        sourceId: String,
    ): BazarrSearchResult =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.searchBazarrSubtitles(current, itemId, sourceId)
        }

    suspend fun downloadBazarrSubtitle(
        session: AuthSession,
        itemId: String,
        sourceId: String,
        matchId: String,
    ) =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.downloadBazarrSubtitle(current, itemId, sourceId, matchId)
        }

    suspend fun episodeNeighbors(session: AuthSession, item: MediaItem): EpisodeNeighbors =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.episodeNeighbors(current, item) }

    suspend fun cancelPlaybackSession(session: AuthSession, sessionId: String) =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.cancelPlaybackSession(current, sessionId)
        }

    suspend fun heartbeatPlaybackViewer(
        session: AuthSession,
        viewerSessionId: String,
        positionSeconds: Double,
        durationSeconds: Double,
        paused: Boolean,
        workerSessionId: String?,
        commandAcks: List<ViewerCommandAck> = emptyList(),
    ): ViewerHeartbeat {
        api.setDeviceId(sessionStore.deviceId())
        return requestAuth.authenticatedCatalogRequest(session) { current ->
            api.heartbeatPlaybackViewer(
                current,
                viewerSessionId,
                positionSeconds,
                durationSeconds,
                paused,
                workerSessionId,
                commandAcks,
            )
        }
    }

    suspend fun endPlaybackViewer(
        session: AuthSession,
        viewerSessionId: String,
    ): ViewerEnd =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.endPlaybackViewer(current, viewerSessionId)
        }

    suspend fun trickplay(session: AuthSession, itemId: String, sourceId: String?) =
        requestAuth.authenticatedCatalogRequest(session) { current -> api.trickplay(current, itemId, sourceId) }

    suspend fun subtitleWebVtt(
        session: AuthSession,
        itemId: String,
        sourceId: String?,
        streamIndex: Int,
    ): String =
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.subtitleWebVtt(current, itemId, sourceId, streamIndex)
        }

    suspend fun reportPlayback(
        session: AuthSession,
        itemId: String,
        positionSeconds: Double,
        isPaused: Boolean,
        playSessionId: String?,
        durationSeconds: Double? = null,
    ) {
        requestAuth.authenticatedCatalogRequest(session) { current ->
            api.reportPlayback(
                current,
                itemId,
                positionSeconds,
                isPaused,
                playSessionId,
                durationSeconds,
            )
        }
        invalidateHomeCache()
    }

    override suspend fun savePlayerEngine(engine: PlayerEngine) =
        sessionStore.savePlayerEngine(engine)

    override suspend fun saveMpvVideoOutput(output: MpvVideoOutput) =
        sessionStore.saveMpvVideoOutput(output)

    override suspend fun saveMpvVideoProfile(profile: MpvVideoProfile) =
        sessionStore.saveMpvVideoProfile(profile)

    override suspend fun saveMpvVideoScaler(scaler: MpvVideoScaler) =
        sessionStore.saveMpvVideoScaler(scaler)

    suspend fun savePlaybackTimeDisplayMode(mode: PlaybackTimeDisplayMode) =
        sessionStore.savePlaybackTimeDisplayMode(mode)

    override suspend fun saveShowDebugIcon(enabled: Boolean) =
        sessionStore.saveShowDebugIcon(enabled)

    override suspend fun saveAutoplayNextEpisode(enabled: Boolean) =
        sessionStore.saveAutoplayNextEpisode(enabled)

    override suspend fun saveAutomaticPictureInPicture(enabled: Boolean) =
        sessionStore.saveAutomaticPictureInPicture(enabled)

    override suspend fun saveCheckForUpdatesOnStartup(enabled: Boolean) =
        sessionStore.saveCheckForUpdatesOnStartup(enabled)

    fun syncplayManager(session: AuthSession): SyncplayManager =
        SyncplaySession.manager(
            session,
            sessionStore,
            SyncplayApi(
                requestAuthenticator = SyncplayRequestAuthenticator(requestAuth::authenticatedSyncplayRequest)
            ),
        )

    override suspend fun loadSubtitleStyle(): SubtitleStyle {
        return sessionStore.cachedSubtitleStyle() ?: DEFAULT_SUBTITLE_STYLE
    }

    override suspend fun saveSubtitleStyle(style: SubtitleStyle): SubtitleStyle {
        val normalized = normalizeSubtitleStyle(style)
        sessionStore.cacheSubtitleStyle(normalized)
        return normalized
    }

    override suspend fun loadWatchHistoryPreference(): Boolean {
        val current = session.first() ?: error("Authentication required")
        return requestAuth.authenticatedOrchestratorRequest(current) { value ->
                orchestratorApi.fetchWatchHistoryPreference(value.serverUrl, value.token)
            }
            .also { sessionStore.saveWatchHistoryEnabled(it) }
    }

    override suspend fun saveWatchHistoryPreference(enabled: Boolean): Boolean {
        val current = session.first() ?: error("Authentication required")
        return requestAuth.authenticatedOrchestratorRequest(current) { value ->
                orchestratorApi.setWatchHistoryPreference(
                    value.serverUrl,
                    value.token,
                    enabled,
                )
            }
            .also { sessionStore.saveWatchHistoryEnabled(it) }
    }

    override suspend fun clearWatchHistory() {
        val current = session.first() ?: error("Authentication required")
        requestAuth.authenticatedOrchestratorRequest(current) { value ->
            orchestratorApi.clearWatchHistory(value.serverUrl, value.token)
        }
        invalidateCatalogState()
    }

    override suspend fun loadPlaybackPreference(): PlaybackPreference =
        playbackPreferenceMutex.withLock {
            val current = session.first() ?: error("Authentication required")
            val cached = playbackPreferenceCache
            if (cached != null && cached.first > System.currentTimeMillis() - 30_000) {
                return@withLock cached.second
            }
            requestAuth.authenticatedOrchestratorRequest(current) { value ->
                    orchestratorApi.fetchPlaybackPreference(value.serverUrl, value.token)
                }
                .also { playbackPreferenceCache = System.currentTimeMillis() to it }
        }

    override suspend fun savePlaybackPreference(
        audioLanguage: String?,
        subtitleLanguage: String?,
    ): PlaybackPreference = playbackPreferenceMutex.withLock {
        val current = session.first() ?: error("Authentication required")
        requestAuth.authenticatedOrchestratorRequest(current) { value ->
                orchestratorApi.setPlaybackPreference(
                    value.serverUrl,
                    value.token,
                    audioLanguage,
                    subtitleLanguage,
                )
            }
            .also { playbackPreferenceCache = System.currentTimeMillis() to it }
    }

    suspend fun home(session: AuthSession, forceRefresh: Boolean = false): HomeData =
        homeMutex.withLock {
            val cached = homeCache
            if (
                !forceRefresh &&
                    cached != null &&
                    cached.first > System.currentTimeMillis() - 30_000
            ) {
                return@withLock cached.second
            }
            requestAuth.authenticatedCatalogRequest(session) { current ->
                    api.fetchHome(current)
                }
                .also { homeCache = System.currentTimeMillis() to it }
        }

    private suspend fun invalidateHomeCache() {
        homeMutex.withLock { homeCache = null }
    }

    private suspend fun invalidateCatalogState() {
        homeMutex.withLock {
            homeCache = null
            _catalogRefreshRevision.value += 1
        }
        playbackPreferenceMutex.withLock { playbackPreferenceCache = null }
    }

    private suspend fun invalidateCatalogMetadata() {
        homeMutex.withLock {
            homeCache = null
            _catalogRefreshRevision.value += 1
        }
        playbackPreferenceMutex.withLock { playbackPreferenceCache = null }
    }
}

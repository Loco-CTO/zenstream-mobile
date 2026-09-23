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
import com.zenstream.zenstreammobile.model.PlaylistData
import com.zenstream.zenstreammobile.model.PlaylistSummary
import com.zenstream.zenstreammobile.model.PlaybackData
import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlaybackTimeDisplayMode
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SearchFilter
import com.zenstream.zenstreammobile.model.SubtitleStyle
import com.zenstream.zenstreammobile.model.ViewerCommandAck
import com.zenstream.zenstreammobile.model.ViewerEnd
import com.zenstream.zenstreammobile.model.ViewerHeartbeat
import java.time.Instant
import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    suspend fun playlists(session: AuthSession): List<PlaylistSummary> = unsupportedPlaylistOperation()

    suspend fun playlist(session: AuthSession, playlistId: String): PlaylistData =
        unsupportedPlaylistOperation()

    suspend fun sharedPlaylist(session: AuthSession, shareToken: String): PlaylistData =
        unsupportedPlaylistOperation()

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

    suspend fun removePlaylistEntry(session: AuthSession, playlistId: String, entryId: String): PlaylistData =
        unsupportedPlaylistOperation()

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
        authenticatedCatalogRequest(session) { current -> api.logout(current) }
    }

    private val homeMutex = Mutex()
    private val interfaceLocaleMutex = Mutex()
    private val playbackPreferenceMutex = Mutex()
    private var homeCache: Pair<Long, HomeData>? = null
    private var playbackPreferenceCache: Pair<Long, PlaybackPreference>? = null
    private val _catalogRefreshRevision = MutableStateFlow(0L)
    override val catalogRefreshRevision: StateFlow<Long> = _catalogRefreshRevision
    val serverUrl: Flow<String?> = sessionStore.serverUrl
    val orchestratorUrl: Flow<String?> = sessionStore.orchestratorUrl
    val session: Flow<AuthSession?> = sessionStore.session
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
    override val checkForUpdatesOnStartup: Flow<Boolean> = sessionStore.checkForUpdatesOnStartup
    override val watchHistoryEnabled: Flow<Boolean> = sessionStore.watchHistoryEnabled

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

    suspend fun authenticate(username: String, password: String): AuthSession {
        val server = sessionStore.currentServerUrl() ?: error("Server URL is not configured")
        return api.authenticate(server, username, password, sessionStore.deviceId()).also {
            sessionStore.saveSession(it)
        }
    }

    suspend fun refreshCurrentAccount(): AuthSession {
        val current = session.first() ?: error("Authentication required")
        val refreshed = authenticatedCatalogRequest(current) { value -> api.refreshAccount(value) }
        saveSessionIfCurrent(current, refreshed)
        return refreshed
    }

    suspend fun uploadAvatar(
        session: AuthSession,
        resolver: ContentResolver,
        uri: Uri,
        crop: AvatarCrop,
    ): AuthSession {
        val version =
            authenticatedCatalogRequest(session) { current ->
                api.uploadAvatar(current, resolver, uri, crop)
            }
        val updated = session.copy(avatarVersion = version)
        saveSessionIfCurrent(session, updated)
        return updated
    }

    suspend fun removeAvatar(session: AuthSession): AuthSession {
        authenticatedCatalogRequest(session) { current -> api.deleteAvatar(current) }
        val updated = session.copy(avatarVersion = null)
        saveSessionIfCurrent(session, updated)
        return updated
    }

    suspend fun changePassword(
        session: AuthSession,
        currentPassword: String,
        newPassword: String,
        confirmNewPassword: String,
    ) {
        authenticatedCatalogRequest(session) { current ->
            api.changePassword(current, currentPassword, newPassword, confirmNewPassword)
        }
    }

    suspend fun syncInterfaceLocale(current: AuthSession) = interfaceLocaleMutex.withLock {
        val mode = interfaceLocaleMode.first()
        val resolvedLocale = sessionStore.resolveInterfaceLocale(mode)
        val remoteLocale =
            authenticatedOrchestratorRequest(current) { value ->
                orchestratorApi.fetchLocale(value.serverUrl, value.token)
            }
        var localeChanged = false
        if (remoteLocale != resolvedLocale) {
            val savedLocale =
                authenticatedOrchestratorRequest(current) { value ->
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
            authenticatedOrchestratorRequest(current) { value ->
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
            authenticatedOrchestratorRequest(current) { value ->
                orchestratorApi.fetchMetadataPreference(value.serverUrl, value.token)
            }
        } catch (error: OrchestratorException) {
            if (error.statusCode == 401) throw error
            null
        }

    private suspend fun refreshAfterUnauthorized(expected: AuthSession): AuthSession? =
        accountRefreshMutex.withLock {
            val current = session.first()
            if (current != null && current.token != expected.token) return@withLock current
            try {
                api.refreshAccessToken(expected).also { sessionStore.saveSession(it) }
            } catch (error: CatalogException) {
                if (error.statusCode == 401 || error.statusCode == 403) {
                    clearSessionIfCurrent(expected)
                }
                null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        }

    private suspend fun <T> authenticatedOrchestratorRequest(
        current: AuthSession,
        block: suspend (AuthSession) -> T,
    ): T =
        try {
            block(current)
        } catch (error: OrchestratorException) {
            if (error.statusCode != 401) throw error
            val refreshed = refreshAfterUnauthorized(current) ?: throw error
            try {
                block(refreshed)
            } catch (retryError: OrchestratorException) {
                if (retryError.statusCode == 401) clearSessionIfCurrent(refreshed)
                throw retryError
            }
        }

    private suspend fun <T> authenticatedCatalogRequest(
        current: AuthSession,
        block: suspend (AuthSession) -> T,
    ): T =
        try {
            block(current)
        } catch (error: CatalogException) {
            if (error.statusCode != 401) throw error
            val refreshed = refreshAfterUnauthorized(current) ?: throw error
            try {
                block(refreshed)
            } catch (retryError: CatalogException) {
                if (retryError.statusCode == 401) clearSessionIfCurrent(refreshed)
                throw retryError
            }
        }

    private suspend fun saveSessionIfCurrent(expected: AuthSession, updated: AuthSession) {
        if (session.first()?.token == expected.token) {
            sessionStore.saveSession(updated)
        }
    }

    override suspend fun loadMetadataPreference(): MetadataPreference {
        val current = session.first() ?: error("Authentication required")
        return authenticatedOrchestratorRequest(current) { value ->
                orchestratorApi.fetchMetadataPreference(value.serverUrl, value.token)
            }
            .also { sessionStore.saveMetadataLanguage(it.effectiveLanguage) }
    }

    override suspend fun saveMetadataPreference(language: String?): MetadataPreference {
        val current = session.first() ?: error("Authentication required")
        return authenticatedOrchestratorRequest(current) { value ->
                orchestratorApi.setMetadataPreference(value.serverUrl, value.token, language)
            }
            .also {
                sessionStore.saveMetadataLanguage(it.effectiveLanguage)
                invalidateCatalogMetadata()
            }
    }

    override suspend fun clearSession() {
        SyncplaySession.clear()
        homeMutex.withLock { homeCache = null }
        playbackPreferenceMutex.withLock { playbackPreferenceCache = null }
        AudioLyricsCache.clear()
        sessionStore.clearSession()
    }

    override suspend fun clearSessionIfCurrent(session: AuthSession) {
        if (this.session.first()?.token != session.token) return
        clearSession()
    }

    suspend fun clearAll() {
        SyncplaySession.clear()
        homeMutex.withLock { homeCache = null }
        playbackPreferenceMutex.withLock { playbackPreferenceCache = null }
        AudioLyricsCache.clear()
        sessionStore.clearAll()
    }

    override suspend fun homeFeatured(session: AuthSession) =
        authenticatedCatalogRequest(session) { current -> api.fetchHomeFeatured(current) }

    override suspend fun homeContinueWatching(session: AuthSession) =
        authenticatedCatalogRequest(session) { current -> api.fetchHomeContinueWatching(current) }

    override suspend fun homeNextUp(session: AuthSession) =
        authenticatedCatalogRequest(session) { current -> api.fetchHomeNextUp(current) }

    override suspend fun homeDerived(session: AuthSession) =
        authenticatedCatalogRequest(session) { current -> api.fetchHomeDerived(current) }

    override suspend fun homeLibraries(session: AuthSession) =
        authenticatedCatalogRequest(session) { current ->
            api.getLibraries(current, CatalogApi.HOME_REQUEST_TIMEOUT_MILLIS)
        }

    override suspend fun homeLibraryData(session: AuthSession, library: Library) =
        authenticatedCatalogRequest(session) { current ->
            api.fetchHomeLibraryData(current, library, CatalogApi.HOME_REQUEST_TIMEOUT_MILLIS)
        }

    override suspend fun libraries(session: AuthSession) =
        authenticatedCatalogRequest(session) { current -> api.getLibraries(current) }

    suspend fun library(
        session: AuthSession,
        library: Library,
    ) = authenticatedCatalogRequest(session) { current -> api.fetchLibraryData(current, library) }

    override suspend fun libraryPage(
        session: AuthSession,
        library: Library,
        startIndex: Int,
        limit: Int,
        sort: LibrarySort,
    ): PagedLibrary =
        authenticatedCatalogRequest(session) { current ->
            api.fetchLibraryPage(current, library, startIndex, limit, sort)
        }

    override suspend fun search(session: AuthSession, query: String, page: Int) =
        authenticatedCatalogRequest(session) { current -> api.search(current, query, page) }

    override suspend fun search(
        session: AuthSession,
        query: String,
        page: Int,
        filter: SearchFilter,
    ) = authenticatedCatalogRequest(session) { current -> api.search(current, query, page, filter) }

    override suspend fun favoritesPage(
        session: AuthSession,
        startIndex: Int,
        limit: Int,
        sort: FavoriteSort,
    ) =
        authenticatedCatalogRequest(session) { current ->
            api.fetchFavoritesPage(current, startIndex, limit, sort)
        }

    override suspend fun watchlist(session: AuthSession): List<MediaItem> =
        authenticatedCatalogRequest(session) { current -> api.fetchWatchlist(current) }

    override suspend fun playlists(session: AuthSession): List<PlaylistSummary> =
        authenticatedCatalogRequest(session) { current -> api.fetchPlaylists(current) }

    override suspend fun playlist(session: AuthSession, playlistId: String): PlaylistData =
        authenticatedCatalogRequest(session) { current -> api.fetchPlaylist(current, playlistId) }

    override suspend fun sharedPlaylist(session: AuthSession, shareToken: String): PlaylistData =
        authenticatedCatalogRequest(session) { current -> api.fetchSharedPlaylist(current, shareToken) }

    override suspend fun createPlaylist(
        session: AuthSession,
        name: String,
        description: String?,
        isPrivate: Boolean,
        entityId: String?,
    ): PlaylistData {
        val result =
            authenticatedCatalogRequest(session) { current ->
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
            authenticatedCatalogRequest(session) { current ->
                api.updatePlaylist(current, playlistId, name, description, isPrivate)
            }
        invalidateCatalogState()
        return result
    }

    override suspend fun deletePlaylist(session: AuthSession, playlistId: String) {
        authenticatedCatalogRequest(session) { current -> api.deletePlaylist(current, playlistId) }
        invalidateCatalogState()
    }

    override suspend fun addPlaylistItems(
        session: AuthSession,
        playlistId: String,
        entityIds: List<String>,
    ): PlaylistData {
        val result =
            authenticatedCatalogRequest(session) { current ->
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
            authenticatedCatalogRequest(session) { current ->
                api.removePlaylistEntry(current, playlistId, entryId)
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
            authenticatedCatalogRequest(session) { current ->
                api.reorderPlaylist(current, playlistId, entryIds)
            }
        invalidateCatalogState()
        return result
    }

    override suspend fun musicAlbum(session: AuthSession, albumId: String): MusicAlbumData =
        authenticatedCatalogRequest(session) { current -> api.musicAlbum(current, albumId) }

    override suspend fun musicArtist(session: AuthSession, artistId: String): MusicArtistData =
        authenticatedCatalogRequest(session) { current -> api.musicArtist(current, artistId) }

    override suspend fun musicArtistTracks(
        session: AuthSession,
        artistId: String,
    ): List<MediaItem> =
        authenticatedCatalogRequest(session) { current -> api.musicArtistTracks(current, artistId) }

    override suspend fun audioLyrics(session: AuthSession, itemId: String): AudioLyrics? =
        AudioLyricsCache.getOrLoad(AudioLyricsCacheKey(session.serverUrl, session.userId, itemId)) {
            authenticatedCatalogRequest(session) { current -> api.audioLyrics(current, itemId) }
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
        authenticatedCatalogRequest(session) { current ->
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
        authenticatedCatalogRequest(session) { current -> api.detail(current, itemId, seasonId) }

    suspend fun catalogItem(
        session: AuthSession,
        itemId: String,
        requestTimeoutMillis: Long? = null,
    ): MediaItem =
        authenticatedCatalogRequest(session) { current ->
            api.catalogItem(current, itemId, requestTimeoutMillis)
        }

    override suspend fun setFavorite(session: AuthSession, itemId: String, favorite: Boolean) {
        authenticatedCatalogRequest(session) { current ->
            api.setFavorite(current, itemId, favorite)
        }
        invalidateCatalogState()
    }

    suspend fun setPlayed(session: AuthSession, itemId: String, played: Boolean) {
        authenticatedCatalogRequest(session) { current -> api.setPlayed(current, itemId, played) }
        invalidateHomeCache()
    }

    override suspend fun setFollowing(session: AuthSession, itemId: String, following: Boolean) {
        authenticatedCatalogRequest(session) { current ->
            api.setFollowing(current, itemId, following)
        }
        invalidateCatalogState()
    }

    override suspend fun calendar(
        session: AuthSession,
        start: Instant,
        end: Instant,
    ): CalendarResponse =
        authenticatedCatalogRequest(session) { current -> api.calendar(current, start, end) }

    override suspend fun setCalendarFollowing(
        session: AuthSession,
        eventId: String,
        following: Boolean,
    ): Boolean {
        val result =
            authenticatedCatalogRequest(session) { current ->
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
        authenticatedCatalogRequest(session) { current ->
            api.notifications(current, limit, cursor)
        }

    suspend fun notificationSummary(session: AuthSession) =
        authenticatedCatalogRequest(session) { current -> api.notificationSummary(current) }

    suspend fun setNotificationRead(
        session: AuthSession,
        notificationId: String,
        read: Boolean,
    ) =
        authenticatedCatalogRequest(session) { current ->
            api.setNotificationRead(current, notificationId, read)
        }

    suspend fun deleteNotification(session: AuthSession, notificationId: String) =
        authenticatedCatalogRequest(session) { current ->
            api.deleteNotification(current, notificationId)
        }

    suspend fun markAllNotificationsRead(session: AuthSession) =
        authenticatedCatalogRequest(session) { current -> api.markAllNotificationsRead(current) }

    suspend fun playback(
        session: AuthSession,
        itemId: String,
        options: PlaybackOptions = PlaybackOptions(),
    ): PlaybackData {
        api.setDeviceId(sessionStore.deviceId())
        return authenticatedCatalogRequest(session) { current ->
            api.playback(current, itemId, options)
        }
    }

    suspend fun playbackSource(session: AuthSession, itemId: String) =
        authenticatedCatalogRequest(session) { current -> api.playbackSource(current, itemId) }

    suspend fun refreshPlaybackAccess(
        session: AuthSession,
        itemId: String,
        sourceId: String,
        playbackSessionId: String? = null,
    ) =
        authenticatedCatalogRequest(session) { current ->
            api.refreshPlaybackAccess(current, itemId, sourceId, playbackSessionId)
        }

    suspend fun bazarrStatus(session: AuthSession, itemId: String, sourceId: String): BazarrStatus =
        authenticatedCatalogRequest(session) { current ->
            api.bazarrStatus(current, itemId, sourceId)
        }

    suspend fun searchBazarrSubtitles(
        session: AuthSession,
        itemId: String,
        sourceId: String,
    ): BazarrSearchResult =
        authenticatedCatalogRequest(session) { current ->
            api.searchBazarrSubtitles(current, itemId, sourceId)
        }

    suspend fun downloadBazarrSubtitle(
        session: AuthSession,
        itemId: String,
        sourceId: String,
        matchId: String,
    ) =
        authenticatedCatalogRequest(session) { current ->
            api.downloadBazarrSubtitle(current, itemId, sourceId, matchId)
        }

    suspend fun episodeNeighbors(session: AuthSession, item: MediaItem): EpisodeNeighbors =
        authenticatedCatalogRequest(session) { current -> api.episodeNeighbors(current, item) }

    suspend fun cancelPlaybackSession(session: AuthSession, sessionId: String) =
        authenticatedCatalogRequest(session) { current ->
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
        return authenticatedCatalogRequest(session) { current ->
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
        authenticatedCatalogRequest(session) { current ->
            api.endPlaybackViewer(current, viewerSessionId)
        }

    suspend fun trickplay(session: AuthSession, itemId: String, sourceId: String?) =
        authenticatedCatalogRequest(session) { current -> api.trickplay(current, itemId, sourceId) }

    suspend fun subtitleWebVtt(
        session: AuthSession,
        itemId: String,
        sourceId: String?,
        streamIndex: Int,
    ): String =
        authenticatedCatalogRequest(session) { current ->
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
        authenticatedCatalogRequest(session) { current ->
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

    override suspend fun saveCheckForUpdatesOnStartup(enabled: Boolean) =
        sessionStore.saveCheckForUpdatesOnStartup(enabled)

    fun syncplayManager(session: AuthSession): SyncplayManager =
        SyncplaySession.manager(session, sessionStore)

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
        return authenticatedOrchestratorRequest(current) { value ->
                orchestratorApi.fetchWatchHistoryPreference(value.serverUrl, value.token)
            }
            .also { sessionStore.saveWatchHistoryEnabled(it) }
    }

    override suspend fun saveWatchHistoryPreference(enabled: Boolean): Boolean {
        val current = session.first() ?: error("Authentication required")
        return authenticatedOrchestratorRequest(current) { value ->
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
        authenticatedOrchestratorRequest(current) { value ->
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
            authenticatedOrchestratorRequest(current) { value ->
                    orchestratorApi.fetchPlaybackPreference(value.serverUrl, value.token)
                }
                .also { playbackPreferenceCache = System.currentTimeMillis() to it }
        }

    override suspend fun savePlaybackPreference(
        audioLanguage: String?,
        subtitleLanguage: String?,
    ): PlaybackPreference = playbackPreferenceMutex.withLock {
        val current = session.first() ?: error("Authentication required")
        authenticatedOrchestratorRequest(current) { value ->
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
            authenticatedCatalogRequest(session) { current ->
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

package com.zenstream.zenstreammobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.zenstream.zenstreammobile.model.AudioQueueSnapshot
import com.zenstream.zenstreammobile.model.AudioRepeatMode
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.FavoriteSort
import com.zenstream.zenstreammobile.model.FavoriteSortBy
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.LibrarySortBy
import com.zenstream.zenstreammobile.model.MpvVideoOutput
import com.zenstream.zenstreammobile.model.MpvVideoProfile
import com.zenstream.zenstreammobile.model.MpvVideoScaler
import com.zenstream.zenstreammobile.model.PlaybackTimeDisplayMode
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SortOrder
import com.zenstream.zenstreammobile.model.SubtitleStyle
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import org.json.JSONObject

internal const val DEFAULT_SESSION_DATA_STORE_NAME = "zenstream_session"
internal const val INSTRUMENTATION_SESSION_DATA_STORE_NAME = "zenstream_instrumentation"

sealed interface StoredSessionState {
    data object Loading : StoredSessionState

    data class Loaded(val session: AuthSession?) : StoredSessionState

    data class TemporarilyUnavailable(val errorType: String) : StoredSessionState
}

private val sessionDataStores = ConcurrentHashMap<String, DataStore<Preferences>>()

private fun sessionDataStore(context: Context, name: String): DataStore<Preferences> {
    val appContext = context.applicationContext ?: context
    val file = appContext.preferencesDataStoreFile(name)
    return sessionDataStores.computeIfAbsent(file.absolutePath) {
        PreferenceDataStoreFactory.create { file }
    }
}

class SessionStore(
    context: Context,
    private val cipher: TokenCipher = TokenCipher(),
    dataStoreName: String = DEFAULT_SESSION_DATA_STORE_NAME,
    private val systemLanguageTags: () -> List<String> = {
        val appContext = context.applicationContext ?: context
        appContext.resources.configuration.locales.toLanguageTags().split(',')
    },
) {
    private val dataStore = sessionDataStore(context, dataStoreName)

    private object Keys {
        val orchestratorUrl = stringPreferencesKey("orchestrator_url")
        val serverUrl = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("encrypted_token")
        val refreshToken = stringPreferencesKey("encrypted_refresh_token")
        val resourceTicket = stringPreferencesKey("encrypted_resource_ticket")
        val artworkTicket = stringPreferencesKey("encrypted_artwork_ticket")
        val accessExpiresAtMillis = longPreferencesKey("access_expires_at_millis")
        val refreshExpiresAtMillis = longPreferencesKey("refresh_expires_at_millis")
        val refreshAttemptId = stringPreferencesKey("refresh_attempt_id")
        val userId = stringPreferencesKey("user_id")
        val username = stringPreferencesKey("username")
        val avatarVersion = stringPreferencesKey("avatar_version")
        val locale = stringPreferencesKey("locale")
        val interfaceLocaleMode = stringPreferencesKey("interface_locale_mode")
        val metadataLanguage = stringPreferencesKey("metadata_language")
        val playerEngine = stringPreferencesKey("player_engine")
        val mpvVideoOutput = stringPreferencesKey("mpv_video_output")
        val mpvVideoProfile = stringPreferencesKey("mpv_video_profile")
        val mpvVideoScaler = stringPreferencesKey("mpv_video_scaler")
        val playbackTimeDisplayMode = stringPreferencesKey("playback_time_display_mode")
        val showDebugIcon = booleanPreferencesKey("show_debug_icon")
        val autoplayNextEpisode = booleanPreferencesKey("autoplay_next_episode")
        val automaticPictureInPicture = booleanPreferencesKey("automatic_picture_in_picture")
        val checkForUpdatesOnStartup = booleanPreferencesKey("check_for_updates_on_startup")
        val watchHistoryEnabled = booleanPreferencesKey("watch_history_enabled")
        val subtitleStyle = stringPreferencesKey("subtitle_style")
        val librarySorts = stringPreferencesKey("library_sorts")
        val syncplayParticipantId = stringPreferencesKey("syncplay_participant_id")
        val syncplayPresenceSequence = longPreferencesKey("syncplay_presence_sequence")
        val deviceId = stringPreferencesKey("device_id")
        val audioVolume = stringPreferencesKey("audio_volume")
        val audioMuted = booleanPreferencesKey("audio_muted")
        val audioShuffle = booleanPreferencesKey("audio_shuffle")
        val audioRepeatMode = stringPreferencesKey("audio_repeat_mode")
        val audioQueueSnapshots = stringPreferencesKey("audio_queue_snapshots")
    }

    // `server_url` is retained as a migration key, but it now always contains
    // the orchestrator origin. Older installs already have the orchestrator
    // origin in its dedicated key.
    val serverUrl: Flow<String?> =
        dataStore.data.map { it[Keys.orchestratorUrl] ?: it[Keys.serverUrl] }

    val orchestratorUrl: Flow<String?> = dataStore.data.map { it[Keys.orchestratorUrl] }

    val interfaceLocaleMode: Flow<InterfaceLocaleMode> =
        dataStore.data
            .map { InterfaceLocaleMode.fromStorageValue(it[Keys.interfaceLocaleMode]) }
            .distinctUntilChanged()

    val locale: Flow<String> =
        interfaceLocaleMode
            .map { mode -> resolveInterfaceLocale(mode, systemLanguageTags()) }
            .distinctUntilChanged()

    val metadataLanguage: Flow<String> =
        dataStore.data.map { it[Keys.metadataLanguage] ?: "en" }.distinctUntilChanged()

    val playerEngine: Flow<PlayerEngine> =
        dataStore.data
            .map { value ->
                runCatching { PlayerEngine.valueOf(value[Keys.playerEngine].orEmpty()) }
                    .getOrDefault(PlayerEngine.MPV)
            }
            .distinctUntilChanged()

    val mpvVideoOutput: Flow<MpvVideoOutput> =
        dataStore.data
            .map { MpvVideoOutput.fromStorageValue(it[Keys.mpvVideoOutput]) }
            .distinctUntilChanged()

    val mpvVideoProfile: Flow<MpvVideoProfile> =
        dataStore.data
            .map { MpvVideoProfile.fromStorageValue(it[Keys.mpvVideoProfile]) }
            .distinctUntilChanged()

    val mpvVideoScaler: Flow<MpvVideoScaler> =
        dataStore.data
            .map { MpvVideoScaler.fromStorageValue(it[Keys.mpvVideoScaler]) }
            .distinctUntilChanged()

    val playbackTimeDisplayMode: Flow<PlaybackTimeDisplayMode> =
        dataStore.data
            .map { PlaybackTimeDisplayMode.fromStorageValue(it[Keys.playbackTimeDisplayMode]) }
            .distinctUntilChanged()

    val showDebugIcon: Flow<Boolean> =
        dataStore.data.map { it[Keys.showDebugIcon] ?: false }.distinctUntilChanged()

    val autoplayNextEpisode: Flow<Boolean> =
        dataStore.data.map { it[Keys.autoplayNextEpisode] ?: true }.distinctUntilChanged()

    val automaticPictureInPicture: Flow<Boolean> =
        dataStore.data.map { it[Keys.automaticPictureInPicture] ?: true }.distinctUntilChanged()

    val checkForUpdatesOnStartup: Flow<Boolean> =
        dataStore.data.map { it[Keys.checkForUpdatesOnStartup] ?: true }.distinctUntilChanged()

    val watchHistoryEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.watchHistoryEnabled] ?: true }.distinctUntilChanged()

    val audioVolume: Flow<Float> =
        dataStore.data
            .map { it[Keys.audioVolume]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f }
            .distinctUntilChanged()

    val audioMuted: Flow<Boolean> =
        dataStore.data.map { it[Keys.audioMuted] ?: false }.distinctUntilChanged()

    val audioShuffle: Flow<Boolean> =
        dataStore.data.map { it[Keys.audioShuffle] ?: false }.distinctUntilChanged()

    val audioRepeatMode: Flow<AudioRepeatMode> =
        dataStore.data
            .map {
                runCatching { AudioRepeatMode.valueOf(it[Keys.audioRepeatMode].orEmpty()) }
                    .getOrDefault(AudioRepeatMode.Off)
            }
            .distinctUntilChanged()

    private val storedSession: Flow<AuthSession?> =
        dataStore.data
            .map { prefs ->
                decodeSession(prefs)
            }
            // Android Keystore can be briefly unavailable while the device is
            // restoring/unlocking. Do not turn that transient condition into a
            // logged-out state; retry the read before exposing an unavailable state.
            .retryWhen { cause, attempt ->
                val retryable = cause is KeyStoreException || cause is UnrecoverableKeyException
                if (retryable && attempt < 4) {
                    delay(250L * (attempt + 1))
                    true
                } else {
                    false
                }
            }
            .distinctUntilChanged()

    val sessionState: Flow<StoredSessionState> =
        storedSession
            .map<AuthSession?, StoredSessionState> { StoredSessionState.Loaded(it) }
            .catch { emit(StoredSessionState.TemporarilyUnavailable(it::class.java.simpleName)) }
            .onStart { emit(StoredSessionState.Loading) }
            .distinctUntilChanged()
            .onEach { state ->
                when (state) {
                    StoredSessionState.Loading -> AuthLifecycleLog.event("session_load_started")
                    is StoredSessionState.Loaded ->
                        AuthLifecycleLog.event("session_loaded", state.session)
                    is StoredSessionState.TemporarilyUnavailable ->
                        AuthLifecycleLog.event(
                            "session_load_unavailable",
                            errorType = state.errorType,
                        )
                }
            }

    val session: Flow<AuthSession?> =
        sessionState
            .filterNot { it is StoredSessionState.Loading }
            .map { state -> (state as? StoredSessionState.Loaded)?.session }
            .distinctUntilChanged()

    private fun decodeSession(prefs: Preferences): AuthSession? {
        val server = prefs[Keys.orchestratorUrl] ?: prefs[Keys.serverUrl]
        val encryptedToken = prefs[Keys.token]
        val userId = prefs[Keys.userId]
        if (server.isNullOrBlank() || encryptedToken.isNullOrBlank() || userId.isNullOrBlank()) {
            return null
        }
        return AuthSession(
            serverUrl = server,
            token = cipher.decrypt(encryptedToken),
            userId = userId,
            username = prefs[Keys.username].orEmpty().ifBlank { "ZenStream" },
            resourceTicket = prefs[Keys.resourceTicket]?.let { cipher.decrypt(it) },
            avatarVersion = prefs[Keys.avatarVersion],
            artworkTicket = prefs[Keys.artworkTicket]?.let { cipher.decrypt(it) },
            refreshToken = prefs[Keys.refreshToken]?.let { cipher.decrypt(it) },
            accessExpiresAtMillis = prefs[Keys.accessExpiresAtMillis],
            refreshExpiresAtMillis = prefs[Keys.refreshExpiresAtMillis],
            refreshAttemptId = prefs[Keys.refreshAttemptId],
        )
    }

    suspend fun saveServerUrl(server: String) {
        dataStore.edit { it[Keys.serverUrl] = normalizeServerUrl(server) }
    }

    suspend fun saveServerConfig(orchestrator: String) {
        dataStore.edit {
            it[Keys.orchestratorUrl] = normalizeServerUrl(orchestrator)
            it[Keys.serverUrl] = normalizeServerUrl(orchestrator)
        }
    }

    suspend fun saveOrchestratorUrl(orchestrator: String) {
        dataStore.edit {
            it[Keys.orchestratorUrl] = normalizeServerUrl(orchestrator)
        }
    }

    suspend fun saveSession(session: AuthSession) {
        dataStore.edit {
            writeSession(it, session)
        }
        AuthLifecycleLog.event("credentials_persisted", session)
    }

    suspend fun beginRefreshAttempt(expected: AuthSession, attemptId: String): AuthSession? {
        var result: AuthSession? = null
        dataStore.edit { prefs ->
            val current = decodeSession(prefs)
            if (!sameSessionGeneration(current, expected)) return@edit
            val activeAttempt = current?.refreshAttemptId ?: attemptId
            prefs[Keys.refreshAttemptId] = activeAttempt
            result = current?.copy(refreshAttemptId = activeAttempt)
        }
        result?.let { AuthLifecycleLog.event("refresh_attempt_persisted", it) }
        return result
    }

    suspend fun saveRefreshedSessionIfCurrent(
        expected: AuthSession,
        refreshed: AuthSession,
    ): Boolean {
        var saved = false
        dataStore.edit { prefs ->
            val current = decodeSession(prefs)
            if (
                sameSessionGeneration(current, expected) &&
                    current?.refreshAttemptId == expected.refreshAttemptId
            ) {
                writeSession(prefs, refreshed.copy(refreshAttemptId = null))
                saved = true
            }
        }
        if (saved) AuthLifecycleLog.event("rotated_credentials_persisted", refreshed)
        return saved
    }

    suspend fun saveSessionIfCurrent(expected: AuthSession, updated: AuthSession): Boolean {
        var saved = false
        dataStore.edit { prefs ->
            val current = decodeSession(prefs)
            if (sameSessionGeneration(current, expected)) {
                writeSession(prefs, updated.copy(refreshAttemptId = current?.refreshAttemptId))
                saved = true
            }
        }
        if (saved) AuthLifecycleLog.event("credentials_updated", updated)
        return saved
    }

    suspend fun clearSessionIfCurrent(session: AuthSession, reason: String): Boolean {
        var cleared = false
        dataStore.edit { prefs ->
            val current = decodeSession(prefs)
            if (
                sameSessionGeneration(current, session) &&
                    current?.refreshAttemptId == session.refreshAttemptId
            ) {
                removeSession(prefs)
                cleared = true
            }
        }
        if (cleared) AuthLifecycleLog.event("credentials_cleared", session, detail = reason)
        return cleared
    }

    private fun writeSession(prefs: MutablePreferences, session: AuthSession) {
        prefs[Keys.serverUrl] = session.serverUrl
        prefs[Keys.token] = cipher.encrypt(session.token)
        session.refreshToken?.let { token ->
            prefs[Keys.refreshToken] = cipher.encrypt(token)
        } ?: prefs.remove(Keys.refreshToken)
        session.accessExpiresAtMillis?.let { expiresAt ->
            prefs[Keys.accessExpiresAtMillis] = expiresAt
        } ?: prefs.remove(Keys.accessExpiresAtMillis)
        session.refreshExpiresAtMillis?.let { expiresAt ->
            prefs[Keys.refreshExpiresAtMillis] = expiresAt
        } ?: prefs.remove(Keys.refreshExpiresAtMillis)
        session.refreshAttemptId?.let { prefs[Keys.refreshAttemptId] = it }
            ?: prefs.remove(Keys.refreshAttemptId)
        session.resourceTicket?.let { ticket ->
            prefs[Keys.resourceTicket] = cipher.encrypt(ticket)
        } ?: prefs.remove(Keys.resourceTicket)
        session.artworkTicket?.let { ticket ->
            prefs[Keys.artworkTicket] = cipher.encrypt(ticket)
        } ?: prefs.remove(Keys.artworkTicket)
        prefs[Keys.userId] = session.userId
        prefs[Keys.username] = session.username
        session.avatarVersion?.let { version ->
            prefs[Keys.avatarVersion] = version
        } ?: prefs.remove(Keys.avatarVersion)
    }

    private fun removeSession(prefs: MutablePreferences) {
        prefs.remove(Keys.token)
        prefs.remove(Keys.refreshToken)
        prefs.remove(Keys.resourceTicket)
        prefs.remove(Keys.artworkTicket)
        prefs.remove(Keys.accessExpiresAtMillis)
        prefs.remove(Keys.refreshExpiresAtMillis)
        prefs.remove(Keys.refreshAttemptId)
        prefs.remove(Keys.userId)
        prefs.remove(Keys.username)
        prefs.remove(Keys.avatarVersion)
    }

    private fun sameSessionGeneration(
        current: AuthSession?,
        expected: AuthSession,
    ): Boolean =
        current != null &&
            current.serverUrl == expected.serverUrl &&
            current.userId == expected.userId &&
            current.token == expected.token &&
            current.refreshToken == expected.refreshToken

    suspend fun saveInterfaceLocaleMode(mode: InterfaceLocaleMode) {
        dataStore.edit { it[Keys.interfaceLocaleMode] = mode.storageValue }
    }

    fun resolveInterfaceLocale(mode: InterfaceLocaleMode): String =
        resolveInterfaceLocale(mode, systemLanguageTags())

    suspend fun saveMetadataLanguage(language: String) {
        dataStore.edit { it[Keys.metadataLanguage] = language.ifBlank { "en" } }
    }

    suspend fun savePlayerEngine(engine: PlayerEngine) {
        dataStore.edit { it[Keys.playerEngine] = engine.name }
    }

    suspend fun saveMpvVideoOutput(output: MpvVideoOutput) {
        dataStore.edit { it[Keys.mpvVideoOutput] = output.storageValue }
    }

    suspend fun saveMpvVideoProfile(profile: MpvVideoProfile) {
        dataStore.edit { it[Keys.mpvVideoProfile] = profile.storageValue }
    }

    suspend fun saveMpvVideoScaler(scaler: MpvVideoScaler) {
        dataStore.edit { it[Keys.mpvVideoScaler] = scaler.storageValue }
    }

    suspend fun savePlaybackTimeDisplayMode(mode: PlaybackTimeDisplayMode) {
        dataStore.edit { it[Keys.playbackTimeDisplayMode] = mode.storageValue }
    }

    suspend fun saveShowDebugIcon(enabled: Boolean) {
        dataStore.edit { it[Keys.showDebugIcon] = enabled }
    }

    suspend fun saveAutoplayNextEpisode(enabled: Boolean) {
        dataStore.edit { it[Keys.autoplayNextEpisode] = enabled }
    }

    suspend fun saveAutomaticPictureInPicture(enabled: Boolean) {
        dataStore.edit { it[Keys.automaticPictureInPicture] = enabled }
    }

    suspend fun saveCheckForUpdatesOnStartup(enabled: Boolean) {
        dataStore.edit { it[Keys.checkForUpdatesOnStartup] = enabled }
    }

    suspend fun saveWatchHistoryEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.watchHistoryEnabled] = enabled }
    }

    suspend fun saveAudioVolume(value: Float) {
        dataStore.edit { it[Keys.audioVolume] = value.coerceIn(0f, 1f).toString() }
    }

    suspend fun saveAudioMuted(value: Boolean) {
        dataStore.edit { it[Keys.audioMuted] = value }
    }

    suspend fun saveAudioShuffle(value: Boolean) {
        dataStore.edit { it[Keys.audioShuffle] = value }
    }

    suspend fun saveAudioRepeatMode(value: AudioRepeatMode) {
        dataStore.edit { it[Keys.audioRepeatMode] = value.name }
    }

    suspend fun loadAudioQueueSnapshot(serverUrl: String, userId: String): AudioQueueSnapshot? {
        val encoded = dataStore.data.first()[Keys.audioQueueSnapshots] ?: return null
        val root = runCatching { JSONObject(encoded) }.getOrNull() ?: return null
        val value = root.optJSONObject(audioQueueScope(serverUrl, userId)) ?: return null
        return audioQueueSnapshotFromJson(value)
    }

    suspend fun saveAudioQueueSnapshot(snapshot: AudioQueueSnapshot) {
        val current =
            dataStore.data.first()[Keys.audioQueueSnapshots]?.let {
                runCatching { JSONObject(it) }.getOrNull()
            } ?: JSONObject()
        current.put(audioQueueScope(snapshot.serverUrl, snapshot.userId), snapshot.toJson())
        dataStore.edit { it[Keys.audioQueueSnapshots] = current.toString() }
    }

    suspend fun clearAudioQueueSnapshot(serverUrl: String, userId: String) {
        val current =
            dataStore.data.first()[Keys.audioQueueSnapshots]?.let {
                runCatching { JSONObject(it) }.getOrNull()
            } ?: return
        current.remove(audioQueueScope(serverUrl, userId))
        dataStore.edit {
            if (current.length() == 0) it.remove(Keys.audioQueueSnapshots)
            else it[Keys.audioQueueSnapshots] = current.toString()
        }
    }

    suspend fun cacheSubtitleStyle(style: SubtitleStyle) {
        dataStore.edit { it[Keys.subtitleStyle] = subtitleStyleToJson(style) }
    }

    suspend fun cachedLibrarySort(userId: String, libraryId: String): LibrarySort? {
        val preferences = dataStore.data.first()
        val stored = preferences[Keys.librarySorts].orEmpty()
        if (stored.isBlank()) return null
        val key = librarySortKey(userId, libraryId)
        val value = runCatching { JSONObject(stored).optJSONObject(key) }.getOrNull() ?: return null
        return runCatching { librarySortFromJson(value) }.getOrNull()
    }

    suspend fun cacheLibrarySort(userId: String, libraryId: String, sort: LibrarySort) {
        val current =
            dataStore.data.first()[Keys.librarySorts]?.let {
                runCatching { JSONObject(it) }.getOrNull()
            } ?: JSONObject()
        current.put(librarySortKey(userId, libraryId), librarySortToJson(sort))
        dataStore.edit { it[Keys.librarySorts] = current.toString() }
    }

    suspend fun cachedFavoriteSort(userId: String): FavoriteSort? {
        val preferences = dataStore.data.first()
        val stored = preferences[Keys.librarySorts].orEmpty()
        if (stored.isBlank()) return null
        val value =
            runCatching { JSONObject(stored).optJSONObject(favoriteSortKey(userId)) }.getOrNull()
                ?: return null
        return runCatching { favoriteSortFromJson(value) }.getOrNull()
    }

    suspend fun cacheFavoriteSort(userId: String, sort: FavoriteSort) {
        val current =
            dataStore.data.first()[Keys.librarySorts]?.let {
                runCatching { JSONObject(it) }.getOrNull()
            } ?: JSONObject()
        current.put(favoriteSortKey(userId), favoriteSortToJson(sort))
        dataStore.edit { it[Keys.librarySorts] = current.toString() }
    }

    suspend fun cachedSubtitleStyle(): SubtitleStyle? {
        val preferences = dataStore.data.first()
        val stored =
            preferences[Keys.subtitleStyle]?.let {
                runCatching { subtitleStyleFromJson(it) }.getOrNull()
            }
        if (stored != null) return stored

        val legacy = legacySubtitleStyleFrom(preferences)
        if (legacy != null) cacheSubtitleStyle(legacy)
        return legacy
    }

    suspend fun clearSession(reason: String = "explicit") {
        // Player engine, time display mode, and subtitle style are device-local
        // preferences. They intentionally survive logout and account changes.
        val current = dataStore.data.first()
        val server = current[Keys.orchestratorUrl] ?: current[Keys.serverUrl]
        val userId = current[Keys.userId]
        val snapshots =
            current[Keys.audioQueueSnapshots]?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (server != null && userId != null) {
            snapshots?.remove(audioQueueScope(server, userId))
        }
        dataStore.edit {
            removeSession(it)
            it.remove(Keys.locale)
            it.remove(Keys.metadataLanguage)
            it.remove(Keys.watchHistoryEnabled)
            if (snapshots == null || snapshots.length() == 0) it.remove(Keys.audioQueueSnapshots)
            else it[Keys.audioQueueSnapshots] = snapshots.toString()
        }
        AuthLifecycleLog.event("credentials_cleared", detail = reason)
    }

    suspend fun clearAll() {
        // Keep player engine, time display mode, and subtitle style when the
        // configured server or account changes; these preferences belong to
        // this installation.
        dataStore.edit {
            it.remove(Keys.orchestratorUrl)
            it.remove(Keys.serverUrl)
            it.remove(Keys.token)
            it.remove(Keys.refreshToken)
            it.remove(Keys.resourceTicket)
            it.remove(Keys.artworkTicket)
            it.remove(Keys.accessExpiresAtMillis)
            it.remove(Keys.refreshExpiresAtMillis)
            it.remove(Keys.refreshAttemptId)
            it.remove(Keys.userId)
            it.remove(Keys.username)
            it.remove(Keys.avatarVersion)
            it.remove(Keys.locale)
            it.remove(Keys.metadataLanguage)
            it.remove(Keys.watchHistoryEnabled)
            it.remove(Keys.librarySorts)
            it.remove(Keys.audioQueueSnapshots)
        }
        AuthLifecycleLog.event("credentials_cleared", detail = "server_or_account_change")
    }

    suspend fun currentServerUrl(): String? = serverUrl.first()

    suspend fun deviceId(): String {
        val existing = dataStore.data.first()[Keys.deviceId]
        if (!existing.isNullOrBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            if (prefs[Keys.deviceId].isNullOrBlank()) prefs[Keys.deviceId] = generated
        }
        return dataStore.data.first()[Keys.deviceId] ?: generated
    }

    suspend fun syncplayParticipantId(): String {
        val existing = dataStore.data.first()[Keys.syncplayParticipantId]
        if (!existing.isNullOrBlank()) return existing
        val generated = java.util.UUID.randomUUID().toString()
        dataStore.edit { prefs ->
            if (prefs[Keys.syncplayParticipantId].isNullOrBlank()) {
                prefs[Keys.syncplayParticipantId] = generated
            }
        }
        return dataStore.data.first()[Keys.syncplayParticipantId] ?: generated
    }

    suspend fun syncplayPresenceSequence(): Long =
        dataStore.data.first()[Keys.syncplayPresenceSequence] ?: 0L

    suspend fun recordSyncplayPresenceSequence(sequence: Long) {
        if (sequence < 0) return
        dataStore.edit { preferences ->
            val current = preferences[Keys.syncplayPresenceSequence] ?: 0L
            if (sequence > current) preferences[Keys.syncplayPresenceSequence] = sequence
        }
    }
}

private fun librarySortKey(userId: String, libraryId: String): String = "$userId\u0000$libraryId"

private fun favoriteSortKey(userId: String): String = "$userId\u0000favorites"

private fun librarySortToJson(sort: LibrarySort): JSONObject =
    JSONObject().put("sortBy", sort.sortBy.name).put("sortOrder", sort.sortOrder.name)

private fun librarySortFromJson(value: JSONObject): LibrarySort =
    LibrarySort(
        sortBy =
            runCatching {
                    LibrarySortBy.valueOf(value.optString("sortBy"))
                }
                .getOrDefault(LibrarySortBy.LastAdded),
        sortOrder =
            runCatching {
                    SortOrder.valueOf(value.optString("sortOrder"))
                }
                .getOrDefault(SortOrder.Descending),
    )

private fun favoriteSortToJson(sort: FavoriteSort): JSONObject =
    JSONObject().put("sortBy", sort.sortBy.name).put("sortOrder", sort.sortOrder.name)

private fun favoriteSortFromJson(value: JSONObject): FavoriteSort =
    FavoriteSort(
        sortBy =
            runCatching { FavoriteSortBy.valueOf(value.optString("sortBy")) }
                .getOrDefault(FavoriteSortBy.Title),
        sortOrder =
            runCatching { SortOrder.valueOf(value.optString("sortOrder")) }
                .getOrDefault(SortOrder.Ascending),
    )

internal fun legacySubtitleStyleFrom(preferences: Preferences): SubtitleStyle? =
    preferences
        .asMap()
        .entries
        .asSequence()
        .filter { it.key.name.startsWith("subtitle_style_") }
        .sortedBy { it.key.name }
        .mapNotNull { (_, value) ->
            (value as? String)?.let { encoded ->
                runCatching { subtitleStyleFromJson(encoded) }.getOrNull()
            }
        }
        .firstOrNull()

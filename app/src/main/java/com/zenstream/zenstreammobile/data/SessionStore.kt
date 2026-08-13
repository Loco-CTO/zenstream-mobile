package com.zenstream.zenstreammobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.FavoriteSort
import com.zenstream.zenstreammobile.model.FavoriteSortBy
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.LibrarySortBy
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import org.json.JSONObject

internal const val DEFAULT_SESSION_DATA_STORE_NAME = "zenstream_session"
internal const val INSTRUMENTATION_SESSION_DATA_STORE_NAME = "zenstream_instrumentation"

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
        val resourceTicket = stringPreferencesKey("encrypted_resource_ticket")
        val userId = stringPreferencesKey("user_id")
        val username = stringPreferencesKey("username")
        val locale = stringPreferencesKey("locale")
        val interfaceLocaleMode = stringPreferencesKey("interface_locale_mode")
        val metadataLanguage = stringPreferencesKey("metadata_language")
        val playerEngine = stringPreferencesKey("player_engine")
        val showDebugIcon = booleanPreferencesKey("show_debug_icon")
        val subtitleStyle = stringPreferencesKey("subtitle_style")
        val librarySorts = stringPreferencesKey("library_sorts")
        val syncplayParticipantId = stringPreferencesKey("syncplay_participant_id")
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
                    .getOrDefault(PlayerEngine.MEDIA3)
            }
            .distinctUntilChanged()

    val showDebugIcon: Flow<Boolean> =
        dataStore.data.map { it[Keys.showDebugIcon] ?: false }.distinctUntilChanged()

    val session: Flow<AuthSession?> =
        dataStore.data
            .map { prefs ->
                val server = prefs[Keys.orchestratorUrl] ?: prefs[Keys.serverUrl]
                val encryptedToken = prefs[Keys.token]
                val userId = prefs[Keys.userId]
                if (
                    server.isNullOrBlank() ||
                        encryptedToken.isNullOrBlank() ||
                        userId.isNullOrBlank()
                ) {
                    return@map null
                }
                AuthSession(
                    server,
                    cipher.decrypt(encryptedToken),
                    userId,
                    prefs[Keys.username].orEmpty().ifBlank { "ZenStream" },
                    prefs[Keys.resourceTicket]?.let { cipher.decrypt(it) },
                )
            }
            // Android Keystore can be briefly unavailable while the device is
            // restoring/unlocking. Do not turn that transient condition into a
            // logged-out state; retry the read before falling back to no session.
            .retryWhen { cause, attempt ->
                val retryable = cause is KeyStoreException || cause is UnrecoverableKeyException
                if (retryable && attempt < 4) {
                    delay(250L * (attempt + 1))
                    true
                } else {
                    false
                }
            }
            .catch { emit(null) }
            .distinctUntilChanged()

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
            it[Keys.serverUrl] = session.serverUrl
            it[Keys.token] = cipher.encrypt(session.token)
            session.resourceTicket?.let { ticket ->
                it[Keys.resourceTicket] = cipher.encrypt(ticket)
            } ?: it.remove(Keys.resourceTicket)
            it[Keys.userId] = session.userId
            it[Keys.username] = session.username
        }
    }

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

    suspend fun saveShowDebugIcon(enabled: Boolean) {
        dataStore.edit { it[Keys.showDebugIcon] = enabled }
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

    suspend fun clearSession() {
        dataStore.edit {
            it.remove(Keys.token)
            it.remove(Keys.resourceTicket)
            it.remove(Keys.userId)
            it.remove(Keys.username)
            it.remove(Keys.locale)
            it.remove(Keys.metadataLanguage)
        }
    }

    suspend fun clearAll() {
        dataStore.edit {
            it.remove(Keys.orchestratorUrl)
            it.remove(Keys.serverUrl)
            it.remove(Keys.token)
            it.remove(Keys.resourceTicket)
            it.remove(Keys.userId)
            it.remove(Keys.username)
            it.remove(Keys.locale)
            it.remove(Keys.metadataLanguage)
            it.remove(Keys.librarySorts)
        }
    }

    suspend fun currentServerUrl(): String? = serverUrl.first()

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

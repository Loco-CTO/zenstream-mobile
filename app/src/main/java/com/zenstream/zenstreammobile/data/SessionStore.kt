package com.zenstream.zenstreammobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zenstream.zenstreammobile.model.AuthSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException

private val Context.sessionDataStore by preferencesDataStore(name = "zenstream_session")

class SessionStore(private val context: Context, private val cipher: TokenCipher = TokenCipher()) {
    private object Keys {
        val orchestratorUrl = stringPreferencesKey("orchestrator_url")
        val serverUrl = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("encrypted_token")
        val userId = stringPreferencesKey("user_id")
        val username = stringPreferencesKey("username")
        val locale = stringPreferencesKey("locale")
    }

    val serverUrl: Flow<String?> = context.sessionDataStore.data.map { it[Keys.serverUrl] }

    val orchestratorUrl: Flow<String?> =
        context.sessionDataStore.data.map { it[Keys.orchestratorUrl] }

    val locale: Flow<String> = context.sessionDataStore.data
        .map { normalizeLocale(it[Keys.locale]) }
        .distinctUntilChanged()

    val session: Flow<AuthSession?> = context.sessionDataStore.data
        .map { prefs ->
            val server = prefs[Keys.serverUrl]
            val encryptedToken = prefs[Keys.token]
            val userId = prefs[Keys.userId]
            if (server.isNullOrBlank() || encryptedToken.isNullOrBlank() || userId.isNullOrBlank()) {
                return@map null
            }
            AuthSession(
                server,
                cipher.decrypt(encryptedToken),
                userId,
                prefs[Keys.username].orEmpty().ifBlank { "ZenStream" })
        }
        // Android Keystore can be briefly unavailable while the device is
        // restoring/unlocking. Do not turn that transient condition into a
        // logged-out state; retry the read before falling back to no session.
        .retryWhen { cause, attempt ->
            val retryable = cause is KeyStoreException ||
                    cause is UnrecoverableKeyException
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
        context.sessionDataStore.edit { it[Keys.serverUrl] = normalizeServerUrl(server) }
    }

    suspend fun saveServerConfig(orchestrator: String, jellyfin: String) {
        context.sessionDataStore.edit {
            it[Keys.orchestratorUrl] = normalizeServerUrl(orchestrator)
            it[Keys.serverUrl] = normalizeServerUrl(jellyfin)
        }
    }

    suspend fun saveOrchestratorUrl(orchestrator: String) {
        context.sessionDataStore.edit {
            it[Keys.orchestratorUrl] = normalizeServerUrl(orchestrator)
        }
    }

    suspend fun saveSession(session: AuthSession) {
        context.sessionDataStore.edit {
            it[Keys.serverUrl] = session.serverUrl
            it[Keys.token] = cipher.encrypt(session.token)
            it[Keys.userId] = session.userId
            it[Keys.username] = session.username
        }
    }

    suspend fun saveLocale(locale: String) {
        context.sessionDataStore.edit { it[Keys.locale] = normalizeLocale(locale) }
    }

    suspend fun clearSession() {
        context.sessionDataStore.edit {
            it.remove(Keys.token)
            it.remove(Keys.userId)
            it.remove(Keys.username)
            it.remove(Keys.locale)
        }
    }

    suspend fun clearAll() {
        context.sessionDataStore.edit {
            it.remove(Keys.orchestratorUrl)
            it.remove(Keys.serverUrl)
            it.remove(Keys.token)
            it.remove(Keys.userId)
            it.remove(Keys.username)
            it.remove(Keys.locale)
        }
    }

    suspend fun currentServerUrl(): String? = serverUrl.first()
}

package com.zenstream.zenstreammobile.offline

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket

enum class ConnectionMode {
    AUTOMATIC,
    OFFLINE,
}

enum class ServerReachability {
    UNKNOWN,
    REACHABLE,
    UNREACHABLE,
    CERTIFICATE_ERROR,
    PERMISSION_DENIED,
}

enum class OnlineAuthentication {
    UNKNOWN,
    AUTHENTICATED,
    NEEDS_SIGN_IN,
}

data class ConnectionState(
    val initialized: Boolean = false,
    val mode: ConnectionMode = ConnectionMode.AUTOMATIC,
    val reachability: ServerReachability = ServerReachability.UNKNOWN,
    val authentication: OnlineAuthentication = OnlineAuthentication.UNKNOWN,
    val profile: String? = null,
)

class OfflinePolicyException : IOException("Network access is disabled in Offline mode.")

/** Shared by HTTP, artwork, sockets, transfers, and reconciliation. */
object OfflineNetworkPolicy {
    @Volatile private var ready = CountDownLatch(0)
    @Volatile
    var allowed: Boolean = true
        private set

    private val clients = Collections.newSetFromMap(WeakHashMap<OkHttpClient, Boolean>())
    private val sockets = Collections.newSetFromMap(WeakHashMap<WebSocket, Boolean>())

    fun initialize() {
        ready = CountDownLatch(1)
        allowed = false
    }

    fun publish(online: Boolean) {
        allowed = online
        ready.countDown()
        if (!online) {
            synchronized(clients) { clients.toList() }.forEach { it.dispatcher.cancelAll() }
            synchronized(sockets) {
                sockets.toList().forEach { it.cancel() }
                sockets.clear()
            }
        }
    }

    fun requireNetwork() {
        if (!ready.await(10, TimeUnit.SECONDS) || !allowed) throw OfflinePolicyException()
    }

    fun client(base: OkHttpClient = OkHttpClient()): OkHttpClient =
        base.newBuilder().addInterceptor(PolicyInterceptor()).build().also {
            synchronized(clients) { clients.add(it) }
        }

    fun track(socket: WebSocket): WebSocket = socket.also {
        synchronized(sockets) { sockets.add(it) }
        if (!allowed) it.cancel()
    }

    private class PolicyInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            requireNetwork()
            val url = chain.request().url.toString()
            return try {
                chain.proceed(chain.request()).also { response ->
                    ConnectionCoordinator.current?.report(url, response.code, null)
                }
            } catch (error: IOException) {
                if (allowed) ConnectionCoordinator.current?.report(url, null, error)
                throw error
            }
        }
    }
}

class ConnectionCoordinator(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences =
        PreferenceDataStoreFactory.create(scope = scope) {
            context.applicationContext.preferencesDataStoreFile("offline_settings")
        }
    private val modeKey = stringPreferencesKey("connection_mode")
    private val profileKey = stringPreferencesKey("retained_profile")
    private val _state = MutableStateFlow(ConnectionState())
    val state = _state.asStateFlow()
    @Volatile var serverUrl: String? = null

    init {
        current = this
        OfflineNetworkPolicy.initialize()
        scope.launch {
            preferences.data
                .catch {
                    OfflineNetworkPolicy.publish(false)
                    _state.update { it.copy(initialized = true, mode = ConnectionMode.OFFLINE) }
                }
                .collect { prefs ->
                    val mode =
                        if (prefs[modeKey] == "offline") ConnectionMode.OFFLINE
                        else ConnectionMode.AUTOMATIC
                    _state.update {
                        it.copy(initialized = true, mode = mode, profile = prefs[profileKey])
                    }
                    OfflineNetworkPolicy.publish(mode == ConnectionMode.AUTOMATIC)
                }
        }
    }

    suspend fun setMode(mode: ConnectionMode) {
        if (mode == ConnectionMode.OFFLINE) {
            OfflineNetworkPolicy.publish(false)
            _state.update { it.copy(mode = mode) }
        }
        preferences.edit {
            it[modeKey] = if (mode == ConnectionMode.OFFLINE) "offline" else "automatic"
        }
    }

    suspend fun selectProfile(id: String) {
        preferences.edit { it[profileKey] = id }
    }

    fun report(url: String, status: Int?, error: Throwable?) {
        val expected = serverUrl?.toHttpUrlOrNull() ?: return
        val actual = url.toHttpUrlOrNull() ?: return
        if (
            expected.host != actual.host ||
                expected.port != actual.port ||
                expected.scheme != actual.scheme
        )
            return
        val reachability =
            when {
                error is SSLException -> ServerReachability.CERTIFICATE_ERROR
                error is SecurityException -> ServerReachability.PERMISSION_DENIED
                error != null || status != null && status >= 500 -> ServerReachability.UNREACHABLE
                else -> ServerReachability.REACHABLE
            }
        _state.update {
            it.copy(
                reachability = reachability,
                authentication =
                    when (status) {
                        401,
                        403 -> OnlineAuthentication.NEEDS_SIGN_IN
                        in 200..299 -> OnlineAuthentication.AUTHENTICATED
                        else -> it.authentication
                    },
            )
        }
    }

    companion object {
        @Volatile
        var current: ConnectionCoordinator? = null
            private set

        suspend fun onlineStartupAllowed(): Boolean =
            current?.state?.first { it.initialized }?.mode != ConnectionMode.OFFLINE
    }
}

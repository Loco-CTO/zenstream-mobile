package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject

private const val REFRESH_EXPIRY_SKEW_MILLIS = 5_000L
private const val MAX_CACHED_REFRESH_RECOVERIES = 2

class RequestAuth(
    private val sessionStore: SessionStore,
    private val api: CatalogApi,
    private val sessionState: Flow<StoredSessionState>,
    private val session: Flow<AuthSession?>,

) {
    private val accountRefreshMutex = Mutex()
    private val _authRefreshState = MutableStateFlow<AuthRefreshState>(AuthRefreshState.Idle)
    val authRefreshState: StateFlow<AuthRefreshState> = _authRefreshState.asStateFlow()
    private val _sessionClearedEvents = MutableSharedFlow<AuthSession>()
    val sessionClearedEvents: SharedFlow<AuthSession> = _sessionClearedEvents.asSharedFlow()

     suspend fun refreshAfterUnauthorized(
        expected: AuthSession,
    ): AuthSession? =
        accountRefreshMutex.withLock {
            val stored = sessionState.first { it !is StoredSessionState.Loading }
            val current = (stored as? StoredSessionState.Loaded)?.session
            if (current == null) {
                if (stored is StoredSessionState.TemporarilyUnavailable) {
                    _authRefreshState.value =
                        AuthRefreshState.TemporarilyUnavailable(
                            expected,
                            stored.errorType,
                        )
                    AuthLifecycleLog.event(
                        "refresh_skipped_storage_unavailable",
                        expected,
                        errorType = stored.errorType,
                    )
                } else {
                    AuthLifecycleLog.event("refresh_skipped_no_session", expected)
                }
                return@withLock null
            }
            if (current.serverUrl != expected.serverUrl || current.userId != expected.userId) {
                AuthLifecycleLog.event("refresh_skipped_session_identity_changed", current)
                return@withLock null
            }
            if (current.token != expected.token || current.refreshToken != expected.refreshToken) {
                AuthLifecycleLog.event("refresh_reused_newer_session", current)
                return@withLock current
            }
            var pending =
                try {
                    sessionStore.beginRefreshAttempt(current, UUID.randomUUID().toString())
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    _authRefreshState.value =
                        AuthRefreshState.TemporarilyUnavailable(
                            current,
                            error::class.java.simpleName,
                        )
                    AuthLifecycleLog.event(
                        "refresh_attempt_persist_failed",
                        current,
                        errorType = error::class.java.simpleName,
                    )
                    throw error
                } ?: return@withLock null
            _authRefreshState.value = AuthRefreshState.Refreshing(pending)
            AuthLifecycleLog.event("refresh_started", pending)
            try {
                repeat(MAX_CACHED_REFRESH_RECOVERIES) { recoveryIndex ->
                    val refreshed = api.refreshAccessToken(pending)
                    AuthLifecycleLog.event(
                        "refresh_response_received",
                        pending,
                        statusCode = 200,
                    )
                    val saved = sessionStore.saveRefreshedSessionIfCurrent(pending, refreshed)
                    if (!saved) {
                        val latest = session.first()
                        _authRefreshState.value = AuthRefreshState.Idle
                        AuthLifecycleLog.event("refresh_result_discarded_session_changed", latest)
                        return@withLock latest?.takeIf {
                            it.serverUrl == expected.serverUrl && it.userId == expected.userId
                        }
                    }
                    val persisted = refreshed.copy(refreshAttemptId = null)
                    AuthLifecycleLog.event(
                        "refresh_succeeded",
                        persisted,
                        attemptId = pending.refreshAttemptId,
                    )
                    val expiresAt = persisted.accessExpiresAtMillis
                    if (
                        expiresAt == null ||
                        expiresAt > System.currentTimeMillis() + REFRESH_EXPIRY_SKEW_MILLIS
                    ) {
                        _authRefreshState.value = AuthRefreshState.Idle
                        return@withLock persisted
                    }
                    if (recoveryIndex == MAX_CACHED_REFRESH_RECOVERIES - 1) {
                        _authRefreshState.value =
                            AuthRefreshState.TemporarilyUnavailable(
                                persisted,
                                "ExpiredRecoveredAccessToken",
                            )
                        throw CatalogException(
                            502,
                            "Refresh recovery returned an expired access token",
                        )
                    }
                    pending =
                        sessionStore.beginRefreshAttempt(
                            persisted,
                            UUID.randomUUID().toString(),
                        )
                            ?: run {
                                _authRefreshState.value = AuthRefreshState.Idle
                                return@withLock session.first()
                            }
                    _authRefreshState.value = AuthRefreshState.Refreshing(pending)
                    AuthLifecycleLog.event("refresh_recovery_rotation_started", pending)
                }
                error("Refresh recovery loop completed without a result")
            } catch (error: CatalogException) {
                if (error.statusCode == 401) {
                    _authRefreshState.value = AuthRefreshState.Rejected(pending)
                    val cleared = clearSessionLocalStateIfCurrent(pending, "refresh_rejected")
                    if (!cleared) {
                        val latest = session.first()
                        if (
                            latest != null &&
                            latest.serverUrl == expected.serverUrl &&
                            latest.userId == expected.userId
                        ) {
                            // A newer login or session rotation won while this
                            // refresh was in flight. Do not let its stale 401
                            // turn that session into a login navigation.
                            _authRefreshState.value = AuthRefreshState.Idle
                            AuthLifecycleLog.event(
                                "refresh_rejection_stale_session_preserved",
                                latest,
                            )
                            return@withLock latest
                        }
                    }
                } else {
                    _authRefreshState.value =
                        AuthRefreshState.TemporarilyUnavailable(
                            session.first() ?: pending,
                            error::class.java.simpleName,
                        )
                    AuthLifecycleLog.event(
                        "refresh_failed_retryable",
                        session.first() ?: pending,
                        statusCode = error.statusCode,
                        errorType = error::class.java.simpleName,
                    )
                    throw error
                }
                null
            } catch (error: CancellationException) {
                _authRefreshState.value =
                    AuthRefreshState.TemporarilyUnavailable(
                        session.first() ?: pending,
                        "RefreshCancelled",
                    )
                AuthLifecycleLog.event(
                    "refresh_cancelled_retryable",
                    session.first() ?: pending,
                    errorType = error::class.java.simpleName,
                )
                throw error
            } catch (error: Exception) {
                _authRefreshState.value =
                    AuthRefreshState.TemporarilyUnavailable(
                        session.first() ?: pending,
                        error::class.java.simpleName,
                    )
                AuthLifecycleLog.event(
                    "refresh_failed_retryable",
                    session.first() ?: pending,
                    errorType = error::class.java.simpleName,
                )
                throw error
            }
        }

    suspend fun <T> authenticatedOrchestratorRequest(
        current: AuthSession,
        block: suspend (AuthSession) -> T,
    ): T {
        val requestSession = currentSessionForRequest(current)
        return try {
            AuthLifecycleLog.firstProtectedRequest(requestSession)
            block(requestSession).also { clearRetryableAuthState(requestSession) }
        } catch (error: OrchestratorException) {
            if (error.statusCode != 401) throw error
            AuthLifecycleLog.event("protected_request_401", requestSession, statusCode = 401)
            val refreshed = refreshAfterUnauthorized(requestSession) ?: throw error
            try {
                block(refreshed).also { clearRetryableAuthState(refreshed) }
            } catch (retryError: OrchestratorException) {
                if (retryError.statusCode == 401) {
                    AuthLifecycleLog.event("protected_retry_401", refreshed, statusCode = 401)
                }
                throw retryError
            }
        }
    }

    suspend fun <T> authenticatedCatalogRequest(
        current: AuthSession,
        block: suspend (AuthSession) -> T,
    ): T {
        val requestSession = currentSessionForRequest(current)
        return try {
            AuthLifecycleLog.firstProtectedRequest(requestSession)
            block(requestSession).also { clearRetryableAuthState(requestSession) }
        } catch (error: CatalogException) {
            if (error.statusCode != 401) throw error
            AuthLifecycleLog.event("protected_request_401", requestSession, statusCode = 401)
            val refreshed = refreshAfterUnauthorized(requestSession) ?: throw error
            try {
                block(refreshed).also { clearRetryableAuthState(refreshed) }
            } catch (retryError: CatalogException) {
                if (retryError.statusCode == 401) {
                    AuthLifecycleLog.event("protected_retry_401", refreshed, statusCode = 401)
                }
                throw retryError
            }
        }
    }

    suspend fun authenticatedSyncplayRequest(
        current: AuthSession,
        block: suspend (AuthSession) -> JSONObject,
    ): JSONObject {
        val requestSession = currentSessionForRequest(current)
        return try {
            AuthLifecycleLog.firstProtectedRequest(requestSession)
            block(requestSession).also { clearRetryableAuthState(requestSession) }
        } catch (error: SyncplayException) {
            if (error.statusCode != 401) throw error
            AuthLifecycleLog.event("protected_request_401", requestSession, statusCode = 401)
            val refreshed =
                try {
                    refreshAfterUnauthorized(requestSession)
                } catch (refreshError: CatalogException) {
                    throw SyncplayException(
                        refreshError.statusCode,
                        refreshError.message ?: "Authentication refresh failed",
                    )
                } ?: throw error
            try {
                block(refreshed).also { clearRetryableAuthState(refreshed) }
            } catch (retryError: SyncplayException) {
                if (retryError.statusCode == 401) {
                    AuthLifecycleLog.event("protected_retry_401", refreshed, statusCode = 401)
                }
                throw retryError
            }
        }
    }

    suspend fun currentSessionForRequest(expected: AuthSession): AuthSession {
        val latest =
            (sessionState.first { it !is StoredSessionState.Loading } as? StoredSessionState.Loaded)
                ?.session
        return latest?.takeIf {
            it.serverUrl == expected.serverUrl && it.userId == expected.userId
        } ?: expected
    }

    fun clearRetryableAuthState(session: AuthSession) {
        val unavailable = _authRefreshState.value as? AuthRefreshState.TemporarilyUnavailable
        if (
            unavailable?.session?.serverUrl == session.serverUrl &&
            unavailable.session.userId == session.userId
        ) {
            _authRefreshState.value = AuthRefreshState.Idle
            AuthLifecycleLog.event("authenticated_request_recovered", session)
        }
    }

    suspend fun clearSessionLocalStateIfCurrent(
        expected: AuthSession,
        reason: String,
    ): Boolean {
        if (sessionStore.clearSessionIfCurrent(expected, reason)) {
            _sessionClearedEvents.emit(expected)
            AuthLifecycleLog.event("refresh_rejected_session_cleared", expected, detail = reason)
            return true
        }
        return false
    }


}
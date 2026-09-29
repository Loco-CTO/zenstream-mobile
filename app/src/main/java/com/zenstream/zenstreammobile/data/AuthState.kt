package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession

enum class AuthPhase {
    RESTORING,
    AUTHENTICATED,
    ACCESS_EXPIRED_REFRESHABLE,
    REFRESHING,
    TEMPORARILY_UNAVAILABLE,
    REJECTED,
    LOGGED_OUT,
}

internal fun isInitialAuthResolutionPending(phase: AuthPhase): Boolean =
    phase in
        setOf(
            AuthPhase.RESTORING,
            AuthPhase.ACCESS_EXPIRED_REFRESHABLE,
            AuthPhase.REFRESHING,
        )

data class AuthState(
    val phase: AuthPhase,
    val session: AuthSession? = null,
    val failureType: String? = null,
)

sealed interface AuthRefreshState {
    data object Idle : AuthRefreshState

    data class Refreshing(val session: AuthSession) : AuthRefreshState

    data class TemporarilyUnavailable(
        val session: AuthSession,
        val failureType: String,
    ) : AuthRefreshState

    data class Rejected(val session: AuthSession) : AuthRefreshState
}

internal fun deriveAuthState(
    stored: StoredSessionState,
    refresh: AuthRefreshState,
    lastKnownSession: AuthSession?,
    nowMillis: Long,
): AuthState {
    when (stored) {
        StoredSessionState.Loading -> return AuthState(AuthPhase.RESTORING)
        is StoredSessionState.TemporarilyUnavailable ->
            return AuthState(
                AuthPhase.TEMPORARILY_UNAVAILABLE,
                session = lastKnownSession,
                failureType = stored.errorType,
            )
        is StoredSessionState.Loaded -> {
            val session = stored.session
            if (session == null) {
                return if (refresh is AuthRefreshState.Rejected) {
                    AuthState(AuthPhase.REJECTED)
                } else {
                    AuthState(AuthPhase.LOGGED_OUT)
                }
            }
            when (refresh) {
                is AuthRefreshState.Refreshing ->
                    if (
                        refresh.session.userId == session.userId &&
                            refresh.session.serverUrl == session.serverUrl
                    ) {
                        return AuthState(AuthPhase.REFRESHING, session)
                    }
                is AuthRefreshState.TemporarilyUnavailable ->
                    if (
                        refresh.session.userId == session.userId &&
                            refresh.session.serverUrl == session.serverUrl
                    ) {
                        return AuthState(
                            AuthPhase.TEMPORARILY_UNAVAILABLE,
                            session,
                            refresh.failureType,
                        )
                    }
                is AuthRefreshState.Rejected ->
                    if (
                        refresh.session.userId == session.userId &&
                            refresh.session.serverUrl == session.serverUrl
                    ) {
                        return AuthState(AuthPhase.REJECTED, session)
                    }
                AuthRefreshState.Idle -> Unit
            }
            val accessExpired = session.accessExpiresAtMillis?.let { it <= nowMillis } == true
            val refreshPresent = !session.refreshToken.isNullOrBlank()
            return if (accessExpired && refreshPresent) {
                AuthState(AuthPhase.ACCESS_EXPIRED_REFRESHABLE, session)
            } else {
                AuthState(AuthPhase.AUTHENTICATED, session)
            }
        }
    }
}

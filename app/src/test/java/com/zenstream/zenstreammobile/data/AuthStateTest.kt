package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.AuthSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthStateTest {
    private val session =
        AuthSession(
            serverUrl = "https://orchestrator.example",
            token = "access-token",
            userId = "user-1",
            username = "User",
            refreshToken = "refresh-token",
            accessExpiresAtMillis = 900L,
            refreshExpiresAtMillis = 10_000L,
        )

    @Test
    fun loadingAndRefreshStatesAreNotReportedAsLoggedOut() {
        assertEquals(
            AuthPhase.RESTORING,
            deriveAuthState(
                    StoredSessionState.Loading,
                    AuthRefreshState.Idle,
                    null,
                    nowMillis = 1_000L,
                )
                .phase,
        )
        assertEquals(
            AuthPhase.REFRESHING,
            deriveAuthState(
                    StoredSessionState.Loaded(session),
                    AuthRefreshState.Refreshing(session),
                    null,
                    nowMillis = 1_000L,
                )
                .phase,
        )
    }

    @Test
    fun expiredAccessTokenWithRefreshTokenRemainsRefreshable() {
        val state =
            deriveAuthState(
                StoredSessionState.Loaded(session),
                AuthRefreshState.Idle,
                null,
                nowMillis = 1_000L,
            )

        assertEquals(AuthPhase.ACCESS_EXPIRED_REFRESHABLE, state.phase)
        assertSame(session, state.session)
    }

    @Test
    fun transientStorageAndRefreshFailuresRetainTheKnownSession() {
        val storageFailure =
            deriveAuthState(
                StoredSessionState.TemporarilyUnavailable("IOException"),
                AuthRefreshState.Idle,
                session,
                nowMillis = 1_000L,
            )
        val refreshFailure =
            deriveAuthState(
                StoredSessionState.Loaded(session),
                AuthRefreshState.TemporarilyUnavailable(session, "SocketTimeoutException"),
                null,
                nowMillis = 1_000L,
            )

        assertEquals(AuthPhase.TEMPORARILY_UNAVAILABLE, storageFailure.phase)
        assertSame(session, storageFailure.session)
        assertEquals(AuthPhase.TEMPORARILY_UNAVAILABLE, refreshFailure.phase)
        assertSame(session, refreshFailure.session)
    }

    @Test
    fun rejectedRefreshAndExplicitLogoutHaveDifferentStates() {
        val rejected =
            deriveAuthState(
                StoredSessionState.Loaded(null),
                AuthRefreshState.Rejected(session),
                null,
                nowMillis = 1_000L,
            )
        val loggedOut =
            deriveAuthState(
                StoredSessionState.Loaded(null),
                AuthRefreshState.Idle,
                null,
                nowMillis = 1_000L,
            )

        assertEquals(AuthPhase.REJECTED, rejected.phase)
        assertEquals(AuthPhase.LOGGED_OUT, loggedOut.phase)
    }

    @Test
    fun initialAuthResolutionWaitsOnlyForStartupRestoreAndRefresh() {
        assertTrue(isInitialAuthResolutionPending(AuthPhase.RESTORING))
        assertTrue(isInitialAuthResolutionPending(AuthPhase.ACCESS_EXPIRED_REFRESHABLE))
        assertTrue(isInitialAuthResolutionPending(AuthPhase.REFRESHING))
        assertFalse(isInitialAuthResolutionPending(AuthPhase.AUTHENTICATED))
        assertFalse(isInitialAuthResolutionPending(AuthPhase.TEMPORARILY_UNAVAILABLE))
        assertFalse(isInitialAuthResolutionPending(AuthPhase.REJECTED))
        assertFalse(isInitialAuthResolutionPending(AuthPhase.LOGGED_OUT))
    }
}

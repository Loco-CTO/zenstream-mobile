package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.data.AuthPhase
import com.zenstream.zenstreammobile.data.AuthState
import com.zenstream.zenstreammobile.model.AuthSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUiStateTest {
    private val session = AuthSession("https://orchestrator.example", "access", "user-1", "User")

    @Test
    fun runtimeRefreshKeepsAuthenticatedMainEligibleWhileStartupGateRemainsExplicit() {
        val refreshing =
            AppUiState(
                loading = true,
                orchestratorUrl = session.serverUrl,
                serverUrl = session.serverUrl,
                session = session,
                authState = AuthState(AuthPhase.REFRESHING, session),
            )

        assertFalse(refreshing.showMain)
        assertTrue(refreshing.copy(loading = false).showMain)
    }

    @Test
    fun definitiveRefreshRejectionStillRoutesToLogin() {
        val rejected =
            AppUiState(
                loading = false,
                orchestratorUrl = session.serverUrl,
                serverUrl = session.serverUrl,
                authState = AuthState(AuthPhase.REJECTED),
            )

        assertFalse(rejected.showMain)
        assertTrue(rejected.showLogin)
    }
}

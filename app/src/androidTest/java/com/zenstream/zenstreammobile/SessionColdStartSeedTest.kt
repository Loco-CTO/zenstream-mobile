package com.zenstream.zenstreammobile

import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.model.AuthSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Seed for the external cold-start controller; never writes over a configured session. */
class SessionColdStartSeedTest {
    @Test
    fun seed() = runBlocking {
        val serverArgument = InstrumentationRegistry.getArguments().getString("coldStartServer")
        assumeTrue(
            "This helper is run by the cold-start controller",
            !serverArgument.isNullOrBlank(),
        )
        val server = requireNotNull(serverArgument)
        val store = SessionStore(InstrumentationRegistry.getInstrumentation().targetContext)
        if (InstrumentationRegistry.getArguments().getString("coldStartCleanup") == "true") {
            check(store.serverUrl.first() == server)
            check(store.session.first()?.userId in listOf(null, "cold-account"))
            store.clearAll()
            store.saveCheckForUpdatesOnStartup(true)
            return@runBlocking
        }
        check(
            store.session.first() == null &&
                store.serverUrl.first() == null &&
                store.orchestratorUrl.first() == null
        )
        val mode = InstrumentationRegistry.getArguments().getString("coldStartMode") ?: "expired"
        val now = System.currentTimeMillis()
        store.saveServerConfig(server)
        store.saveCheckForUpdatesOnStartup(false)
        val session =
            when (mode) {
                "valid" ->
                    AuthSession(
                        serverUrl = server,
                        token = "cold-valid-access",
                        userId = "cold-account",
                        username = "Cold Account",
                        refreshToken = "cold-valid-refresh",
                        accessExpiresAtMillis = now + 3_600_000,
                        refreshExpiresAtMillis = now + 86_400_000,
                    )
                "revoked" ->
                    AuthSession(
                        serverUrl = server,
                        token = "cold-old-access",
                        userId = "cold-account",
                        username = "Cold Account",
                        refreshToken = "cold-revoked-refresh",
                        accessExpiresAtMillis = now - 60_000,
                        refreshExpiresAtMillis = now + 86_400_000,
                    )
                "expired" ->
                    AuthSession(
                        serverUrl = server,
                        token = "cold-old-access",
                        userId = "cold-account",
                        username = "Cold Account",
                        refreshToken = "cold-old-refresh",
                        accessExpiresAtMillis = now - 60_000,
                        refreshExpiresAtMillis = now + 86_400_000,
                    )
                else -> error("Unsupported cold-start session mode: $mode")
            }
        store.saveSession(session)
    }
}

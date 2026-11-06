package com.zenstream.zenstreammobile

import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.model.AuthSession
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Run on a disposable test installation: this exercises the activity's real session store. */
class SessionRestorationJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val server = MockWebServer()
    private val available = AtomicBoolean(false)
    private val timeoutOnce = AtomicBoolean(true)
    private val revoked = AtomicBoolean(false)
    private val authorizedLibraryRequests = AtomicInteger()
    private lateinit var store: SessionStore
    private var ownsStore = false
    private var previousUpdatePreference = true
    private var activity: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() = runBlocking {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    if (path == "/api/auth/refresh") {
                        if (revoked.get()) return MockResponse().setResponseCode(401)
                        if (timeoutOnce.getAndSet(false)) {
                            return MockResponse().setHeadersDelay(15, TimeUnit.SECONDS)
                        }
                        if (!available.get()) return MockResponse().setResponseCode(503)
                        return json(
                            """{"token":"new-access","refreshToken":"new-refresh","expiresAt":"${Instant.now().plusSeconds(3600)}","refreshExpiresAt":"${Instant.now().plusSeconds(86400)}","user":{"id":"account-1","username":"Test Account"}}"""
                        )
                    }
                    if (request.getHeader("Authorization") != "Bearer new-access") {
                        return MockResponse().setResponseCode(401)
                    }
                    return when (path) {
                        "/api/auth/bootstrap",
                        "/api/auth/me" ->
                            json("""{"user":{"id":"account-1","username":"Test Account"}}""")
                        "/api/catalog/libraries" -> {
                            authorizedLibraryRequests.incrementAndGet()
                            json(
                                """{"libraries":[{"id":"movies","name":"Test Movies","type":"movies"}]}"""
                            )
                        }
                        else -> json("{}")
                    }
                }
            }
        server.start()
        store = SessionStore(context)
        check(
            store.session.first() == null &&
                store.serverUrl.first() == null &&
                store.orchestratorUrl.first() == null
        ) {
            "Session restoration tests require a disposable app installation"
        }
        ownsStore = true
        previousUpdatePreference = store.checkForUpdatesOnStartup.first()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= 37) {
            automation
                .executeShellCommand(
                    "pm grant ${context.packageName} android.permission.ACCESS_LOCAL_NETWORK"
                )
                .close()
        }
        if (Build.VERSION.SDK_INT >= 33) {
            automation
                .executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
                )
                .close()
        }
        store.clearAll()
        val url = server.url("/").toString().trimEnd('/')
        store.saveServerConfig(url)
        store.saveCheckForUpdatesOnStartup(false)
        store.saveSession(
            AuthSession(
                serverUrl = url,
                token = "old-access",
                userId = "account-1",
                username = "Test Account",
                refreshToken = "old-refresh",
                accessExpiresAtMillis = System.currentTimeMillis() - 60_000,
                refreshExpiresAtMillis = System.currentTimeMillis() + 86_400_000,
            )
        )
    }

    @After
    fun tearDown() = runBlocking {
        activity?.close()
        if (ownsStore) {
            store.clearAll()
            store.saveCheckForUpdatesOnStartup(previousUpdatePreference)
        }
        server.shutdown()
    }

    @Test
    fun restartRetainsSessionAndRetryRecoversLibrary() {
        activity = ActivityScenario.launch(MainActivity::class.java)
        val failed = context.getString(R.string.library_load_failed)
        compose.waitUntil(60000) {
            compose
                .onAllNodes(androidx.compose.ui.test.hasText(failed))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(failed).assertIsDisplayed()
        assertEquals("account-1", runBlocking { store.session.first()?.userId })
        activity?.moveToState(Lifecycle.State.CREATED)
        activity?.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText(failed).assertIsDisplayed()
        activity?.close()
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(30000) {
            compose
                .onAllNodes(androidx.compose.ui.test.hasText(failed))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        available.set(true)
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()
        compose.onNodeWithText(context.getString(R.string.library)).performClick()
        compose.waitUntil(30000) {
            compose
                .onAllNodes(androidx.compose.ui.test.hasText("Test Movies"))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onAllNodesWithText("Test Movies").onFirst().assertIsDisplayed()
        assertEquals("account-1", runBlocking { store.session.first()?.userId })
        assertEquals("new-refresh", runBlocking { store.session.first()?.refreshToken })
    }

    @Test
    fun revokedSessionRequiresSignInAfterRestart() {
        revoked.set(true)
        activity = ActivityScenario.launch(MainActivity::class.java)
        val login = context.getString(R.string.login_description)
        compose.waitUntil(30000) {
            compose
                .onAllNodes(androidx.compose.ui.test.hasText(login))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(login).assertIsDisplayed()
        assertNull(runBlocking { store.session.first() })
        assertEquals(0, authorizedLibraryRequests.get())
        activity?.close()
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(30000) {
            compose
                .onAllNodes(androidx.compose.ui.test.hasText(login))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(login).assertIsDisplayed()
    }

    @Test
    fun explicitLogoutDoesNotRestoreSessionAfterRestart() {
        timeoutOnce.set(false)
        available.set(true)
        activity = ActivityScenario.launch(MainActivity::class.java)
        val myPage = context.getString(R.string.my_page)
        compose.waitUntil(30000) {
            compose.onAllNodes(hasText(myPage)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(myPage).performClick()
        val logout = context.getString(R.string.logout)
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasText(logout))
        compose.onNodeWithText(logout).performClick()
        compose.onNodeWithText(context.getString(R.string.logout_remove_downloads)).performClick()
        val login = context.getString(R.string.login_description)
        compose.waitUntil(30000) {
            compose.onAllNodes(hasText(login)).fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(runBlocking { store.session.first() })
        activity?.close()
        activity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(30000) {
            compose.onAllNodes(hasText(login)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(login).assertIsDisplayed()
    }

    private fun json(body: String) =
        MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}

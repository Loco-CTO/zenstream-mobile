package com.zenstream.zenstreammobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.SubtitleStyle
import java.net.UnknownHostException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OrchestratorApiHttpTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun patchesLocaleWithBearerAuthentication() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"locale\":\"ja\"}"))

        val result =
            OrchestratorApi(OkHttpClient())
                .setLocale(server.url("/").toString().trimEnd('/'), "test-token", "ja")

        assertEquals("ja", result)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/api/preferences/locale", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("ja", JSONObject(request.body.readUtf8()).getString("locale"))
    }

    @Test
    fun fetchesConfiguredPublicWebUrlWithoutAuthentication() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"publicWebUrl\":\" https://web.example.com/ \"}"))

        val result =
            OrchestratorApi(OkHttpClient())
                .fetchPublicWebUrl(server.url("/").toString().trimEnd('/'))

        assertEquals("https://web.example.com", result)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/config/public-web-url", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun returnsNoPublicWebUrlWhenNotConfigured() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"publicWebUrl\":\" \"}"))

        val result =
            OrchestratorApi(OkHttpClient())
                .fetchPublicWebUrl(server.url("/").toString().trimEnd('/'))

        assertNull(result)
    }

    @Test
    fun repositoryClearsOnlyAfterDefinitiveRefreshRejection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store =
            SessionStore(
                context,
                dataStoreName = "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_orchestrator_auth",
                systemLanguageTags = { listOf("en-GB") },
            )
        store.clearAll()
        store.saveInterfaceLocaleMode(InterfaceLocaleMode.Automatic)
        val serverUrl = server.url("/").toString().trimEnd('/')
        val session = AuthSession(serverUrl, "test-token", "user-1", "Test")
        store.saveServerConfig(serverUrl)
        store.saveSession(session)
        val repository = CatalogRepository(CatalogApi(), store, OrchestratorApi(OkHttpClient()))

        server.enqueue(MockResponse().setResponseCode(403))
        runCatching { repository.syncInterfaceLocale(session) }
        assertNotNull(store.session.first())

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        runCatching { repository.syncInterfaceLocale(session) }
        assertNull(store.session.first())
        store.clearAll()
        store.saveInterfaceLocaleMode(InterfaceLocaleMode.Automatic)
    }

    @Test
    fun expiredAccessWithValidRefreshRotatesAndRetriesProtectedRequestOnce() = runBlocking {
        val session = refreshableSession(server.url("/").toString().trimEnd('/'))
        val (repository, store) = createRepository("expired_access", session)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(refreshResponse("access-after", "refresh-after"))
        server.enqueue(MockResponse().setBody("{\"enabled\":true}"))

        assertEquals(true, repository.loadWatchHistoryPreference())

        val original = server.takeRequest()
        val refresh = server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("/api/preferences/watch-history", original.path)
        assertEquals("/api/auth/refresh", refresh.path)
        assertEquals("/api/preferences/watch-history", retry.path)
        assertEquals("Bearer access-after", retry.getHeader("Authorization"))
        val attemptId = JSONObject(refresh.body.readUtf8()).getString("refreshAttemptId")
        assertTrue(runCatching { UUID.fromString(attemptId) }.isSuccess)
        val persisted = store.session.first()!!
        assertEquals("access-after", persisted.token)
        assertEquals("refresh-after", persisted.refreshToken)
        assertNull(persisted.refreshAttemptId)
        assertEquals(3, server.requestCount)
        store.clearAll()
    }

    @Test
    fun transientRefreshFailureRetainsCredentialsAndReusesTheSameAttempt() = runBlocking {
        val session = refreshableSession(server.url("/").toString().trimEnd('/'))
        val (repository, store) = createRepository("refresh_retry", session)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(503))

        val failed = runCatching { repository.loadWatchHistoryPreference() }.exceptionOrNull()
        assertEquals(503, (failed as CatalogException).statusCode)
        val pending = store.session.first()!!
        assertEquals("access-before", pending.token)
        assertEquals("refresh-before", pending.refreshToken)
        assertTrue(!pending.refreshAttemptId.isNullOrBlank())

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(refreshResponse("access-after", "refresh-after"))
        server.enqueue(MockResponse().setBody("{\"enabled\":true}"))
        assertEquals(true, repository.loadWatchHistoryPreference())

        server.takeRequest() // The first protected request.
        val failedAttempt = JSONObject(server.takeRequest().body.readUtf8())
        server.takeRequest() // The retried protected request.
        val retriedAttempt = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(
            failedAttempt.getString("refreshAttemptId"),
            retriedAttempt.getString("refreshAttemptId"),
        )
        assertEquals("access-after", store.session.first()?.token)
        store.clearAll()
    }

    @Test
    fun rateLimitedRefreshRetainsCredentialsAndPendingAttempt() = runBlocking {
        val session = refreshableSession(server.url("/").toString().trimEnd('/'))
        val (repository, store) = createRepository("refresh_429", session)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(429))

        val failed = runCatching { repository.loadWatchHistoryPreference() }.exceptionOrNull()
        assertEquals(429, (failed as CatalogException).statusCode)
        val persisted = store.session.first()!!
        assertEquals("access-before", persisted.token)
        assertEquals("refresh-before", persisted.refreshToken)
        assertTrue(!persisted.refreshAttemptId.isNullOrBlank())
        store.clearAll()
    }

    @Test
    fun timeoutDuringRefreshRetainsAttemptForLaterSuccess() = runBlocking {
        val session = refreshableSession(server.url("/").toString().trimEnd('/'))
        val client = OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build()
        val (repository, store) = createRepository("refresh_timeout", session, client)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(
            refreshResponse("access-too-late", "refresh-too-late")
                .setHeadersDelay(400, TimeUnit.MILLISECONDS)
        )

        val failed = runCatching { repository.loadWatchHistoryPreference() }.exceptionOrNull()
        assertTrue(failed is CatalogException || failed is java.net.SocketTimeoutException)
        val pending = store.session.first()!!
        assertEquals("access-before", pending.token)
        assertEquals("refresh-before", pending.refreshToken)
        val attemptId = pending.refreshAttemptId
        assertTrue(!attemptId.isNullOrBlank())

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(refreshResponse("access-after", "refresh-after"))
        server.enqueue(MockResponse().setBody("{\"enabled\":true}"))
        assertEquals(true, repository.loadWatchHistoryPreference())
        server.takeRequest()
        val refresh = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(attemptId, refresh.getString("refreshAttemptId"))
        assertEquals("access-after", store.session.first()?.token)
        store.clearAll()
    }

    @Test
    fun lostRefreshResponseKeepsAttemptAvailableForRecovery() = runBlocking {
        val session = refreshableSession(server.url("/").toString().trimEnd('/'))
        val (repository, store) = createRepository("refresh_disconnect", session)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        runCatching { repository.loadWatchHistoryPreference() }
        val pending = store.session.first()!!
        val attemptId = pending.refreshAttemptId
        assertEquals("access-before", pending.token)
        assertTrue(!attemptId.isNullOrBlank())

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(refreshResponse("access-after", "refresh-after"))
        server.enqueue(MockResponse().setBody("{\"enabled\":true}"))
        assertEquals(true, repository.loadWatchHistoryPreference())

        server.takeRequest()
        val lostResponseRequest = JSONObject(server.takeRequest().body.readUtf8())
        server.takeRequest()
        val recoveredRequest = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(
            lostResponseRequest.getString("refreshAttemptId"),
            recoveredRequest.getString("refreshAttemptId"),
        )
        assertEquals(attemptId, recoveredRequest.getString("refreshAttemptId"))
        assertEquals("access-after", store.session.first()?.token)
        store.clearAll()
    }

    @Test
    fun pendingAttemptRecoversAfterSessionStoreAndRepositoryRecreation() = runBlocking {
        val session = refreshableSession(server.url("/").toString().trimEnd('/'))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dataStoreName =
            "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_cold_refresh_${UUID.randomUUID()}"
        val originalStore = SessionStore(context, dataStoreName = dataStoreName)
        originalStore.clearAll()
        originalStore.saveServerConfig(session.serverUrl)
        originalStore.saveSession(session)
        val firstRepository =
            CatalogRepository(
                CatalogApi(OkHttpClient()),
                originalStore,
                OrchestratorApi(OkHttpClient()),
            )
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        runCatching { firstRepository.loadWatchHistoryPreference() }
        val interruptedAttempt = originalStore.session.first()!!.refreshAttemptId
        assertTrue(!interruptedAttempt.isNullOrBlank())

        // Recreate storage and repository objects as the process would on cold launch.
        val restoredStore = SessionStore(context, dataStoreName = dataStoreName)
        val restoredSession = restoredStore.session.first()!!
        assertEquals(interruptedAttempt, restoredSession.refreshAttemptId)
        val restoredRepository =
            CatalogRepository(
                CatalogApi(OkHttpClient()),
                restoredStore,
                OrchestratorApi(OkHttpClient()),
            )
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(refreshResponse("access-after", "refresh-after"))
        server.enqueue(MockResponse().setBody("{\"enabled\":true}"))

        assertEquals(true, restoredRepository.loadWatchHistoryPreference())
        server.takeRequest() // The protected request before the lost response.
        val lostAttempt = JSONObject(server.takeRequest().body.readUtf8())
        server.takeRequest() // Cold-launch protected request.
        val recoveredAttempt = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(
            lostAttempt.getString("refreshAttemptId"),
            recoveredAttempt.getString("refreshAttemptId"),
        )
        assertEquals(interruptedAttempt, recoveredAttempt.getString("refreshAttemptId"))
        assertEquals("access-after", restoredStore.session.first()?.token)
        assertNull(restoredStore.session.first()?.refreshAttemptId)
        restoredStore.clearAll()
    }

    @Test
    fun dnsFailureDuringRefreshRetainsAttemptForLaterSuccess() = runBlocking {
        val session = refreshableSession(server.url("/").toString().trimEnd('/'))
        val failRefreshOnce = AtomicBoolean(true)
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    if (
                        chain.request().url.encodedPath == "/api/auth/refresh" &&
                            failRefreshOnce.compareAndSet(true, false)
                    ) {
                        throw UnknownHostException("injected DNS failure")
                    }
                    chain.proceed(chain.request())
                }
                .build()
        val (repository, store) = createRepository("refresh_dns", session, client)
        server.enqueue(MockResponse().setResponseCode(401))

        val failed = runCatching { repository.loadWatchHistoryPreference() }.exceptionOrNull()
        assertTrue(failed is UnknownHostException || failed is CatalogException)
        val pending = store.session.first()!!
        val attemptId = pending.refreshAttemptId
        assertEquals("access-before", pending.token)
        assertEquals("refresh-before", pending.refreshToken)
        assertTrue(!attemptId.isNullOrBlank())

        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(refreshResponse("access-after", "refresh-after"))
        server.enqueue(MockResponse().setBody("{\"enabled\":true}"))
        assertEquals(true, repository.loadWatchHistoryPreference())
        server.takeRequest() // The next protected request.
        assertEquals(
            attemptId,
            JSONObject(server.takeRequest().body.readUtf8()).getString("refreshAttemptId"),
        )
        assertEquals("access-after", store.session.first()?.token)
        store.clearAll()
    }

    @Test
    fun startupRestoreAndConcurrentRequestsShareOneRefresh() = runBlocking {
        val session = refreshableSession(server.url("/").toString().trimEnd('/'))
        val (repository, store) = createRepository("refresh_startup_concurrent", session)
        val firstThreeRequests = CountDownLatch(3)
        val protectedRequests = AtomicInteger()
        val bootstrapRequests = AtomicInteger()
        val refreshRequests = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path?.substringBefore('?')) {
                        "/api/preferences/watch-history" -> {
                            if (protectedRequests.incrementAndGet() <= 2) {
                                firstThreeRequests.countDown()
                                check(firstThreeRequests.await(5, TimeUnit.SECONDS))
                                MockResponse().setResponseCode(401)
                            } else {
                                MockResponse().setBody("{\"enabled\":true}")
                            }
                        }
                        "/api/auth/bootstrap" -> {
                            if (bootstrapRequests.incrementAndGet() == 1) {
                                firstThreeRequests.countDown()
                                check(firstThreeRequests.await(5, TimeUnit.SECONDS))
                                MockResponse().setResponseCode(401)
                            } else {
                                MockResponse()
                                    .setBody(
                                        JSONObject()
                                            .put("token", "access-after")
                                            .put("refreshToken", "refresh-after")
                                            .put(
                                                "user",
                                                JSONObject()
                                                    .put("id", "user-1")
                                                    .put("username", "Test"),
                                            )
                                            .toString()
                                    )
                            }
                        }
                        "/api/auth/refresh" -> {
                            refreshRequests.incrementAndGet()
                            refreshResponse("access-after", "refresh-after")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
            }

        val results = coroutineScope {
            listOf(
                    async { repository.refreshCurrentAccount() },
                    async { repository.loadWatchHistoryPreference() },
                    async { repository.loadWatchHistoryPreference() },
                )
                .awaitAll()
        }

        assertEquals("access-after", (results[0] as AuthSession).token)
        assertEquals(listOf(true, true), results.drop(1))
        assertEquals(1, refreshRequests.get())
        assertEquals(4, protectedRequests.get())
        assertEquals(2, bootstrapRequests.get())
        assertEquals(7, server.requestCount)
        assertEquals("access-after", store.session.first()?.token)
        store.clearAll()
    }

    @Test
    fun definitiveRefreshRejectionClearsButProtectedRetry401DoesNot() = runBlocking {
        val rejectedSession = refreshableSession(server.url("/").toString().trimEnd('/'))
        val (rejectedRepository, rejectedStore) =
            createRepository("refresh_rejected", rejectedSession)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        val rejectedError =
            runCatching { rejectedRepository.loadWatchHistoryPreference() }.exceptionOrNull()
        assertEquals(401, (rejectedError as OrchestratorException).statusCode)
        assertNull(rejectedStore.session.first())
        rejectedStore.clearAll()

        val retrySession = refreshableSession(server.url("/").toString().trimEnd('/'))
        val (retryRepository, retryStore) = createRepository("retry_401", retrySession)
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(refreshResponse("access-after", "refresh-after"))
        server.enqueue(MockResponse().setResponseCode(401))

        val retryError =
            runCatching { retryRepository.loadWatchHistoryPreference() }.exceptionOrNull()
        assertEquals(401, (retryError as OrchestratorException).statusCode)
        assertEquals("access-after", retryStore.session.first()?.token)
        assertEquals("refresh-after", retryStore.session.first()?.refreshToken)
        assertNull(retryStore.session.first()?.refreshAttemptId)
        assertEquals(5, server.requestCount)
        retryStore.clearAll()
    }

    @Test
    fun explicitLogoutDuringRefreshLeavesNoPersistedCredentials() = runBlocking {
        val session = refreshableSession(server.url("/").toString().trimEnd('/'))
        val (repository, store) = createRepository("logout_during_refresh", session)
        val refreshStarted = CountDownLatch(1)
        val allowRefreshResponse = CountDownLatch(1)
        val protectedRequests = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path?.substringBefore('?')) {
                        "/api/preferences/watch-history" ->
                            if (protectedRequests.incrementAndGet() == 1) {
                                MockResponse().setResponseCode(401)
                            } else {
                                MockResponse().setBody("{\"enabled\":true}")
                            }
                        "/api/auth/refresh" -> {
                            refreshStarted.countDown()
                            check(allowRefreshResponse.await(5, TimeUnit.SECONDS))
                            refreshResponse("access-after", "refresh-after")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
            }

        val protectedRequest = async { repository.loadWatchHistoryPreference() }
        assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
        val logoutStarted = CompletableDeferred<Unit>()
        val logout = launch {
            logoutStarted.complete(Unit)
            repository.clearSession("explicit")
        }
        logoutStarted.await()
        allowRefreshResponse.countDown()

        assertEquals(true, protectedRequest.await())
        logout.join()
        assertNull(store.session.first())
        store.clearAll()
    }

    @Test
    fun staleRefreshRejectionKeepsNewerSessionAndRetriesWithIt() = runBlocking {
        val original = refreshableSession(server.url("/").toString().trimEnd('/'))
        val (repository, store) = createRepository("stale_refresh_rejection", original)
        val refreshStarted = CountDownLatch(1)
        val allowRefreshRejection = CountDownLatch(1)
        val protectedRequests = AtomicInteger()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.path?.substringBefore('?')) {
                        "/api/preferences/watch-history" ->
                            if (protectedRequests.incrementAndGet() == 1) {
                                MockResponse().setResponseCode(401)
                            } else {
                                MockResponse().setBody("{\"enabled\":true}")
                            }
                        "/api/auth/refresh" -> {
                            refreshStarted.countDown()
                            check(allowRefreshRejection.await(5, TimeUnit.SECONDS))
                            MockResponse().setResponseCode(401)
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
            }

        val protectedRequest = async { repository.loadWatchHistoryPreference() }
        assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
        val newerSession =
            original.copy(
                token = "access-from-newer-session",
                refreshToken = "refresh-from-newer-session",
                accessExpiresAtMillis = System.currentTimeMillis() + 900_000,
            )
        store.saveSession(newerSession)
        allowRefreshRejection.countDown()

        assertEquals(true, protectedRequest.await())
        assertEquals("access-from-newer-session", store.session.first()?.token)
        assertTrue(repository.authRefreshState.value !is AuthRefreshState.Rejected)
        server.takeRequest()
        server.takeRequest()
        assertEquals(
            "Bearer access-from-newer-session",
            server.takeRequest().getHeader("Authorization"),
        )
        store.clearAll()
    }

    @Test
    fun repositorySubtitleStyleUsesDeviceStorageWithoutOrchestratorRequests() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store =
            SessionStore(
                context,
                dataStoreName = "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_subtitle_local",
                systemLanguageTags = { listOf("en-GB") },
            )
        store.clearAll()
        store.cacheSubtitleStyle(DEFAULT_SUBTITLE_STYLE)
        val serverUrl = server.url("/").toString().trimEnd('/')
        store.saveServerConfig(serverUrl)
        store.saveSession(AuthSession(serverUrl, "test-token", "user-1", "Test"))
        val repository = CatalogRepository(CatalogApi(), store, OrchestratorApi(OkHttpClient()))
        val style = SubtitleStyle(fontFamily = "mono", textScale = 140f)

        assertEquals(DEFAULT_SUBTITLE_STYLE, repository.loadSubtitleStyle())
        assertEquals(style, repository.saveSubtitleStyle(style))
        assertEquals(style, repository.loadSubtitleStyle())
        assertEquals(0, server.requestCount)
        store.clearAll()
    }

    private suspend fun createRepository(
        suffix: String,
        session: AuthSession,
        catalogClient: OkHttpClient = OkHttpClient(),
    ): Pair<CatalogRepository, SessionStore> {
        val store =
            SessionStore(
                InstrumentationRegistry.getInstrumentation().targetContext,
                dataStoreName =
                    "${INSTRUMENTATION_SESSION_DATA_STORE_NAME}_${suffix}_${UUID.randomUUID()}",
                systemLanguageTags = { listOf("en-GB") },
            )
        store.clearAll()
        store.saveServerConfig(session.serverUrl)
        store.saveSession(session)
        return CatalogRepository(
            CatalogApi(catalogClient),
            store,
            OrchestratorApi(OkHttpClient()),
        ) to store
    }

    private fun refreshableSession(serverUrl: String) =
        AuthSession(
            serverUrl = serverUrl,
            token = "access-before",
            userId = "user-1",
            username = "Test",
            refreshToken = "refresh-before",
            accessExpiresAtMillis = System.currentTimeMillis() - 60_000,
            refreshExpiresAtMillis = System.currentTimeMillis() + 86_400_000,
        )

    private fun refreshResponse(token: String, refreshToken: String) =
        MockResponse()
            .setBody(
                JSONObject()
                    .put("token", token)
                    .put("refreshToken", refreshToken)
                    .put("expiresAt", Instant.now().plusSeconds(900).toString())
                    .put("refreshExpiresAt", Instant.now().plusSeconds(86_400).toString())
                    .put("user", JSONObject().put("id", "user-1").put("username", "Test"))
                    .toString()
            )
}

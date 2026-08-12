package com.zenstream.zenstreammobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.model.AuthSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun repositoryClearsOnlyUnauthorizedOrchestratorSessions() = runBlocking {
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
        runCatching { repository.syncInterfaceLocale(session) }
        assertNull(store.session.first())
        store.clearAll()
        store.saveInterfaceLocaleMode(InterfaceLocaleMode.Automatic)
    }
}

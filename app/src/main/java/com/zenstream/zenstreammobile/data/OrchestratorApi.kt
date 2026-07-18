package com.zenstream.zenstreammobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class OrchestratorApi(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    suspend fun fetchJellyfinUrl(orchestratorUrl: String): String = withContext(Dispatchers.IO) {
        val orchestrator = normalizeServerUrl(orchestratorUrl)
        val request = Request.Builder()
            .url("$orchestrator/api/config".toHttpUrl())
            .header("Accept", "application/json")
            .get()
            .build()
        val response = httpClient.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) throw OrchestratorException(
                it.code,
                "Orchestrator request failed with ${it.code}"
            )
            parseMobileConfig(it.body?.string().orEmpty())
        }
    }
}

fun parseMobileConfig(body: String): String {
    val jellyfinUrl = JSONObject(body).optString("jellyfinUrl").takeIf { it.isNotBlank() }
        ?: error("Orchestrator did not return a Jellyfin URL")
    return normalizeConfiguredJellyfinUrl(jellyfinUrl)
}

fun normalizeConfiguredJellyfinUrl(value: String): String = normalizeServerUrl(value)

class OrchestratorException(val statusCode: Int, message: String) : Exception(message)

package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.SubtitleStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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

    suspend fun fetchLocale(orchestratorUrl: String, token: String): String =
        withContext(Dispatchers.IO) {
            val orchestrator = normalizeServerUrl(orchestratorUrl)
            val request = Request.Builder()
                .url("$orchestrator/api/preferences/locale".toHttpUrl())
                .header("Accept", "application/json")
                .header("X-Jellyfin-Token", token)
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) throw OrchestratorException(
                    it.code,
                    "Orchestrator request failed with ${it.code}"
                )
                parseLocale(it.body?.string().orEmpty())
            }
        }

    suspend fun fetchSubtitleStyle(orchestratorUrl: String, token: String): SubtitleStyle =
        withContext(Dispatchers.IO) {
            requestSubtitle(orchestratorUrl, token, "GET", null)
        }

    suspend fun saveSubtitleStyle(
        orchestratorUrl: String,
        token: String,
        style: SubtitleStyle,
    ): SubtitleStyle = withContext(Dispatchers.IO) {
        requestSubtitle(orchestratorUrl, token, "PATCH", subtitleStyleToJson(style))
    }

    private fun requestSubtitle(
        orchestratorUrl: String,
        token: String,
        method: String,
        body: String?,
    ): SubtitleStyle {
        val request = Request.Builder()
            .url("${normalizeServerUrl(orchestratorUrl)}/api/preferences/subtitles".toHttpUrl())
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("X-Jellyfin-Token", token)
            .method(method, body?.toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use {
            if (!it.isSuccessful) throw OrchestratorException(
                it.code,
                "Orchestrator request failed with ${it.code}"
            )
            return subtitleStyleFromJson(it.body?.string().orEmpty())
        }
    }
}

fun parseMobileConfig(body: String): String {
    val jellyfinUrl = JSONObject(body).optString("jellyfinUrl").takeIf { it.isNotBlank() }
        ?: error("Orchestrator did not return a Jellyfin URL")
    return normalizeConfiguredJellyfinUrl(jellyfinUrl)
}

fun normalizeConfiguredJellyfinUrl(value: String): String = normalizeServerUrl(value)

fun parseLocale(body: String): String {
    val locale = JSONObject(body).optString("locale").takeIf { it.isNotBlank() }
        ?: error("Orchestrator did not return a locale")
    if (!isSupportedLocale(locale)) error("Orchestrator returned an unsupported locale")
    return locale
}

class OrchestratorException(val statusCode: Int, message: String) : Exception(message)

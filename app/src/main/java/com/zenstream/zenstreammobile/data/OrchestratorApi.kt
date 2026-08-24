package com.zenstream.zenstreammobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class PlaybackLanguageOption(
    val value: String,
    val label: String,
)

data class PlaybackPreference(
    val audioLanguage: String?,
    val subtitleLanguage: String?,
    val audioLanguages: List<PlaybackLanguageOption>,
    val subtitleLanguages: List<PlaybackLanguageOption>,
)

class OrchestratorApi(private val httpClient: OkHttpClient = OkHttpClient()) {
    suspend fun fetchConfig(orchestratorUrl: String) =
        withContext(Dispatchers.IO) {
            val orchestrator = normalizeServerUrl(orchestratorUrl)
            val request =
                Request.Builder()
                    .url("$orchestrator/api/config".toHttpUrl())
                    .header("Accept", "application/json")
                    .get()
                    .build()
            val response = httpClient.newCall(request).execute()
            response.use {
                if (!it.isSuccessful)
                    throw OrchestratorException(
                        it.code,
                        "Orchestrator request failed with ${it.code}",
                    )
                parseProxyConfig(it.body?.string().orEmpty())
            }
        }

    suspend fun fetchLocale(orchestratorUrl: String, token: String): String =
        withContext(Dispatchers.IO) {
            parseLocale(
                authenticatedJson(orchestratorUrl, token, "/api/preferences/locale").toString()
            )
        }

    suspend fun setLocale(orchestratorUrl: String, token: String, locale: String): String =
        withContext(Dispatchers.IO) {
            require(isSupportedLocale(locale)) { "Unsupported interface locale" }
            parseLocale(
                authenticatedJson(
                        orchestratorUrl,
                        token,
                        "/api/preferences/locale",
                        "PATCH",
                        JSONObject().put("locale", locale).toString(),
                    )
                    .toString()
            )
        }

    suspend fun fetchMetadataPreference(
        orchestratorUrl: String,
        token: String,
    ): MetadataPreference =
        withContext(Dispatchers.IO) {
            val languages = authenticatedJson(orchestratorUrl, token, "/api/metadata/languages")
            val preference =
                authenticatedJson(orchestratorUrl, token, "/api/preferences/metadata-language")
            MetadataPreference(
                languages =
                    languages
                        .optJSONArray("languages")
                        ?.let { array -> List(array.length()) { array.optString(it) } }
                        .orEmpty(),
                explicitLanguage =
                    preference.optString("language").takeIf {
                        preference.optString("mode") == "explicit"
                    },
                effectiveLanguage = preference.optString("language").ifBlank { "en" },
            )
        }

    suspend fun setMetadataPreference(
        orchestratorUrl: String,
        token: String,
        language: String?,
    ): MetadataPreference =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("language", language ?: JSONObject.NULL).toString()
            val value =
                authenticatedJson(
                    orchestratorUrl,
                    token,
                    "/api/preferences/metadata-language",
                    "PATCH",
                    body,
                )
            val languages = authenticatedJson(orchestratorUrl, token, "/api/metadata/languages")
            MetadataPreference(
                languages =
                    languages
                        .optJSONArray("languages")
                        ?.let { array -> List(array.length()) { array.optString(it) } }
                        .orEmpty(),
                explicitLanguage =
                    value.optString("language").takeIf { value.optString("mode") == "explicit" },
                effectiveLanguage = value.optString("language").ifBlank { "en" },
            )
        }

    suspend fun fetchPlaybackPreference(
        orchestratorUrl: String,
        token: String,
    ): PlaybackPreference =
        withContext(Dispatchers.IO) {
            parsePlaybackPreference(
                authenticatedJson(orchestratorUrl, token, "/api/preferences/playback")
            )
        }

    suspend fun setPlaybackPreference(
        orchestratorUrl: String,
        token: String,
        audioLanguage: String?,
        subtitleLanguage: String?,
    ): PlaybackPreference =
        withContext(Dispatchers.IO) {
            parsePlaybackPreference(
                authenticatedJson(
                    orchestratorUrl,
                    token,
                    "/api/preferences/playback",
                    "PATCH",
                    JSONObject()
                        .put("audioLanguage", audioLanguage ?: JSONObject.NULL)
                        .put("subtitleLanguage", subtitleLanguage ?: JSONObject.NULL)
                        .toString(),
                )
            )
        }

    suspend fun fetchWatchHistoryPreference(
        orchestratorUrl: String,
        token: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val value = authenticatedJson(orchestratorUrl, token, "/api/preferences/watch-history")
            check(value.has("enabled") && !value.isNull("enabled")) {
                "Orchestrator returned an invalid watch history preference"
            }
            value.optBoolean("enabled")
        }

    suspend fun setWatchHistoryPreference(
        orchestratorUrl: String,
        token: String,
        enabled: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val value =
                authenticatedJson(
                    orchestratorUrl,
                    token,
                    "/api/preferences/watch-history",
                    "PATCH",
                    JSONObject().put("enabled", enabled).toString(),
                )
            check(value.has("enabled") && !value.isNull("enabled")) {
                "Orchestrator returned an invalid watch history preference"
            }
            value.optBoolean("enabled")
        }

    suspend fun clearWatchHistory(orchestratorUrl: String, token: String) {
        withContext(Dispatchers.IO) {
            authenticatedJson(orchestratorUrl, token, "/api/account/watch-history", "DELETE")
        }
    }

    private fun authenticatedJson(
        serverUrl: String,
        token: String,
        path: String,
        method: String = "GET",
        body: String? = null,
    ): JSONObject {
        val request =
            Request.Builder()
                .url("${normalizeServerUrl(serverUrl)}$path".toHttpUrl())
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .method(
                    method,
                    if (method == "GET") null
                    else (body ?: "{}").toRequestBody("application/json".toMediaType()),
                )
                .build()
        httpClient.newCall(request).execute().use {
            if (!it.isSuccessful)
                throw OrchestratorException(it.code, "Orchestrator request failed with ${it.code}")
            return JSONObject(it.body?.string().orEmpty().ifBlank { "{}" })
        }
    }
}

data class MetadataPreference(
    val languages: List<String>,
    val explicitLanguage: String?,
    val effectiveLanguage: String,
)

private fun parsePlaybackPreference(value: JSONObject): PlaybackPreference {
    fun nullableString(key: String): String? =
        value.opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() }

    fun options(key: String): List<PlaybackLanguageOption> {
        val array = value.optJSONArray(key) ?: return emptyList()
        return List(array.length()) { index ->
                val option = array.optJSONObject(index) ?: JSONObject()
                PlaybackLanguageOption(
                    value = option.optString("value"),
                    label = option.optString("label").ifBlank { option.optString("value") },
                )
            }
            .filter { it.value.isNotBlank() }
    }
    return PlaybackPreference(
        audioLanguage = nullableString("audioLanguage"),
        subtitleLanguage = nullableString("subtitleLanguage"),
        audioLanguages = options("audioLanguages"),
        subtitleLanguages = options("subtitleLanguages"),
    )
}

fun parseProxyConfig(body: String) {
    if (!Regex("\\\"catalog\\\"\\s*:\\s*true").containsMatchIn(body))
        error("Orchestrator does not support the catalog")
}

fun parseLocale(body: String): String {
    val locale =
        JSONObject(body).optString("locale").takeIf { it.isNotBlank() }
            ?: error("Orchestrator did not return a locale")
    if (!isSupportedLocale(locale)) error("Orchestrator returned an unsupported locale")
    return locale
}

class OrchestratorException(val statusCode: Int, message: String) : Exception(message)

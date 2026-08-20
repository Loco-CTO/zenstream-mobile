package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class AppUpdate(
    val version: String,
    val releaseUrl: String,
    val downloadUrl: String,
)

interface UpdateSource {
    suspend fun checkForUpdate(): AppUpdate?
}

class GitHubUpdateChecker(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val currentVersion: String = BuildConfig.ZENSTREAM_VERSION,
) : UpdateSource {
    override suspend fun checkForUpdate(): AppUpdate? =
        withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url(GITHUB_RELEASE_API_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "ZenStream-Android/$currentVersion")
                    .get()
                    .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                parseLatestReleaseUpdate(response.body.string(), currentVersion)
            }
        }
}

internal fun parseLatestReleaseUpdate(body: String, currentVersion: String): AppUpdate? {
    val release = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val tag = release.optString("tag_name").trim().takeIf { it.isNotBlank() } ?: return null
    val latest = parseSemanticVersion(tag) ?: return null
    val current = parseSemanticVersion(currentVersion) ?: return null
    if (latest <= current) return null

    val expectedAssetName = "zenstream-mobile-$tag.apk"
    val assets = release.optJSONArray("assets") ?: return null
    val asset =
        (0 until assets.length())
            .asSequence()
            .mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name") == expectedAssetName }
            ?: (0 until assets.length())
                .asSequence()
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull {
                    it.optString("name").startsWith("zenstream-mobile-") &&
                        it.optString("name").endsWith(".apk", ignoreCase = true)
                }
            ?: return null

    val downloadUrl = asset.optString("browser_download_url").trim()
    if (!downloadUrl.startsWith(GITHUB_RELEASE_DOWNLOAD_PREFIX)) return null

    val releaseUrl =
        release.optString("html_url").trim().takeIf {
            it.startsWith(GITHUB_RELEASE_PAGE_PREFIX)
        } ?: "$GITHUB_RELEASE_PAGE_PREFIX$tag"
    return AppUpdate(
        version = latest.toDisplayString(),
        releaseUrl = releaseUrl,
        downloadUrl = downloadUrl,
    )
}

internal fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
    val latest = parseSemanticVersion(latestVersion) ?: return false
    val current = parseSemanticVersion(currentVersion) ?: return false
    return latest > current
}

private data class SemanticVersion(val major: Long, val minor: Long, val patch: Long) :
    Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int =
        compareValuesBy(
            this,
            other,
            SemanticVersion::major,
            SemanticVersion::minor,
            SemanticVersion::patch,
        )

    fun toDisplayString(): String = "$major.$minor.$patch"
}

private fun parseSemanticVersion(value: String): SemanticVersion? {
    val match =
        Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").matchEntire(value.trim()) ?: return null
    return SemanticVersion(
        major = match.groupValues[1].toLongOrNull() ?: return null,
        minor = match.groupValues[2].toLongOrNull() ?: return null,
        patch = match.groupValues[3].toLongOrNull() ?: return null,
    )
}

private const val GITHUB_RELEASE_API_URL =
    "https://api.github.com/repos/Loco-CTO/zenstream-mobile/releases/latest"
private const val GITHUB_RELEASE_PAGE_PREFIX =
    "https://github.com/Loco-CTO/zenstream-mobile/releases/"
private const val GITHUB_RELEASE_DOWNLOAD_PREFIX =
    "https://github.com/Loco-CTO/zenstream-mobile/releases/download/"

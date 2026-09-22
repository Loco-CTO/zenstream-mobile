package com.zenstream.zenstreammobile.data

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val NATIVE_APP_SCHEME = "zenstream"
private const val NATIVE_APP_HOST = "open"

sealed interface NativeAppDestination {
    data object Home : NativeAppDestination

    data object Library : NativeAppDestination

    data object Favorites : NativeAppDestination

    data object Notifications : NativeAppDestination

    data class Album(val albumId: String, val trackId: String? = null) : NativeAppDestination

    data class Artist(val artistId: String) : NativeAppDestination

    data class Detail(val itemId: String) : NativeAppDestination
}

data class NativeAppDeepLink(
    val serverUrl: String,
    val destination: NativeAppDestination,
)

fun parseNativeAppDeepLink(value: String?): NativeAppDeepLink? {
    val uri = value?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    if (!uri.scheme.equals(NATIVE_APP_SCHEME, ignoreCase = true)) return null
    if (!uri.host.equals(NATIVE_APP_HOST, ignoreCase = true)) return null

    val parameters = queryParameters(uri.rawQuery)
    val serverUrl =
        parameters["server"]?.let { server ->
            runCatching { normalizeServerUrl(server) }.getOrNull()
        } ?: return null
    val target = parameters["target"]?.takeIf { it.startsWith("/") } ?: return null

    return NativeAppDeepLink(
        serverUrl = serverUrl,
        destination = parseNativeAppDestination(target) ?: NativeAppDestination.Home,
    )
}

private fun parseNativeAppDestination(target: String): NativeAppDestination? {
    val targetUri = runCatching { URI(target) }.getOrNull() ?: return null
    if (targetUri.isAbsolute) return null
    val segments = targetUri.path.orEmpty().split('/').filter(String::isNotEmpty)
    if (segments.isEmpty()) return NativeAppDestination.Home
    val parameters = queryParameters(targetUri.rawQuery)

    return when {
        segments.size == 1 && segments[0] == "library" -> NativeAppDestination.Library
        segments.size == 1 && segments[0] == "favorites" -> NativeAppDestination.Favorites
        segments.size == 1 && segments[0] == "notifications" -> NativeAppDestination.Notifications
        segments.size == 2 && segments[0] == "album" && segments[1].isNotBlank() ->
            NativeAppDestination.Album(
                albumId = segments[1],
                trackId = parameters["trackId"]?.takeIf { it.isNotBlank() },
            )
        segments.size == 2 && segments[0] == "artist" && segments[1].isNotBlank() ->
            NativeAppDestination.Artist(segments[1])
        segments.size == 2 &&
            segments[0] in setOf("collection", "play", "show") &&
            segments[1].isNotBlank() -> NativeAppDestination.Detail(segments[1])
        segments.size == 4 &&
            segments[0] == "show" &&
            segments[2] == "episode" &&
            segments[3].isNotBlank() -> NativeAppDestination.Detail(segments[3])
        else -> null
    }
}

private fun queryParameters(rawQuery: String?): Map<String, String> =
    rawQuery
        .orEmpty()
        .split('&')
        .asSequence()
        .mapNotNull { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val key = decodeComponent(pair.substring(0, separator)) ?: return@mapNotNull null
            val value = decodeComponent(pair.substring(separator + 1)) ?: return@mapNotNull null
            key to value
        }
        .toMap()

private fun decodeComponent(value: String): String? =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull()

fun sameNativeAppServer(left: String, right: String): Boolean {
    val leftUri = runCatching { URI(normalizeServerUrl(left)) }.getOrNull() ?: return false
    val rightUri = runCatching { URI(normalizeServerUrl(right)) }.getOrNull() ?: return false
    return leftUri.scheme.equals(rightUri.scheme, ignoreCase = true) &&
        leftUri.host.equals(rightUri.host, ignoreCase = true) &&
        effectivePort(leftUri) == effectivePort(rightUri) &&
        leftUri.rawPath.orEmpty().trimEnd('/') == rightUri.rawPath.orEmpty().trimEnd('/') &&
        leftUri.rawQuery == rightUri.rawQuery
}

private fun effectivePort(uri: URI): Int =
    when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

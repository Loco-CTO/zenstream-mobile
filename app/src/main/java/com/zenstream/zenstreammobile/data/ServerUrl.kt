package com.zenstream.zenstreammobile.data

import java.net.URI

fun normalizeServerUrl(input: String): String {
    val value = input.trim().removeSuffix("/")
    require(value.isNotBlank()) { "Server URL is required" }
    val uri = URI(value)
    require(uri.scheme.equals("https", ignoreCase = true) || isLocalHttpHost(uri)) {
        "Server URL must use HTTPS"
    }
    require(!uri.host.isNullOrBlank()) { "Server URL must include a host" }
    require(uri.userInfo == null && uri.fragment == null) { "Server URL contains unsupported components" }
    return value
}

private fun isLocalHttpHost(uri: URI): Boolean {
    if (!uri.scheme.equals("http", ignoreCase = true)) return false
    return uri.host.equals("localhost", ignoreCase = true) ||
            uri.host == "10.0.2.2" ||
            uri.host == "10.0.3.2"
}

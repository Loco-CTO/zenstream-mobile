package com.zenstream.zenstreammobile.data

import java.net.URI

fun normalizeServerUrl(input: String): String {
    val value = input.trim().removeSuffix("/")
    require(value.isNotBlank()) { "Server URL is required" }
    val uri = URI(value)
    require(uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) {
        "Server URL must use HTTP or HTTPS"
    }
    require(!uri.host.isNullOrBlank()) { "Server URL must include a host" }
    require(uri.userInfo == null && uri.fragment == null) { "Server URL contains unsupported components" }
    return value
}

package com.zenstream.zenstreammobile.data

import android.os.Build
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val ACCESS_LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
private const val LOCAL_SERVER_DNS_TIMEOUT_MILLIS = 1_500L
private val LOCAL_SERVER_HOST_SUFFIXES =
    setOf("localhost", "local", "lan", "internal", "intranet", "home.arpa")

internal enum class LocalNetworkPermissionStatus {
    RESOLVING,
    NOT_REQUIRED,
    REQUIRED,
}

internal fun initialLocalNetworkPermissionStatus(
    serverUrl: String?,
    sdkInt: Int = Build.VERSION.SDK_INT,
): LocalNetworkPermissionStatus {
    if (serverUrl.isNullOrBlank() || sdkInt < 37) {
        return LocalNetworkPermissionStatus.NOT_REQUIRED
    }
    if (isLocalNetworkServer(serverUrl)) return LocalNetworkPermissionStatus.REQUIRED

    val host = serverHost(serverUrl) ?: return LocalNetworkPermissionStatus.NOT_REQUIRED
    return if (isAddressLiteral(host)) {
        LocalNetworkPermissionStatus.NOT_REQUIRED
    } else {
        LocalNetworkPermissionStatus.RESOLVING
    }
}

internal fun resolvedLocalNetworkPermissionStatus(
    serverRequiresPermission: Boolean
): LocalNetworkPermissionStatus =
    if (serverRequiresPermission) {
        LocalNetworkPermissionStatus.REQUIRED
    } else {
        LocalNetworkPermissionStatus.NOT_REQUIRED
    }

internal fun canUseServerWithLocalNetworkPermission(
    status: LocalNetworkPermissionStatus,
    permissionGranted: Boolean,
): Boolean =
    status != LocalNetworkPermissionStatus.RESOLVING &&
        (status != LocalNetworkPermissionStatus.REQUIRED || permissionGranted)

internal suspend fun serverRequiresLocalNetworkPermission(
    serverUrl: String,
    sdkInt: Int = Build.VERSION.SDK_INT,
    resolveHost: (String) -> Array<InetAddress> = InetAddress::getAllByName,
): Boolean {
    when (initialLocalNetworkPermissionStatus(serverUrl, sdkInt)) {
        LocalNetworkPermissionStatus.NOT_REQUIRED -> return false
        LocalNetworkPermissionStatus.REQUIRED -> return true
        LocalNetworkPermissionStatus.RESOLVING -> Unit
    }

    val host = serverHost(serverUrl) ?: return false

    // DNS lookup runs off the main thread and the caller waits at most 1.5 seconds. A timed-out
    // lookup may finish on its IO worker later, but it cannot hold up the connection screen.
    return withTimeoutOrNull(LOCAL_SERVER_DNS_TIMEOUT_MILLIS) {
        withContext(Dispatchers.IO) {
            runCatching { resolveHost(host).any(::isLocalAddress) }.getOrDefault(false)
        }
    } ?: false
}

internal fun isLocalNetworkServer(serverUrl: String): Boolean {
    val host = serverHost(serverUrl) ?: return false

    return isLocalServerHostname(host) || isLocalAddressLiteral(host)
}

private fun isLocalAddressLiteral(host: String): Boolean {
    // Only parse numeric address literals here. Hostnames are resolved separately with a
    // bounded background lookup in serverRequiresLocalNetworkPermission.
    if (!isAddressLiteral(host)) return false

    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
    return isLocalAddress(address)
}

private fun isLocalServerHostname(host: String): Boolean =
    !isAddressLiteral(host) &&
        (LOCAL_SERVER_HOST_SUFFIXES.any { host == it || host.endsWith(".$it") } ||
            !host.contains('.'))

private fun serverHost(serverUrl: String): String? =
    runCatching { URI(normalizeServerUrl(serverUrl)).host }
        .getOrNull()
        ?.removeSurrounding("[", "]")
        ?.substringBefore("%25")
        ?.substringBefore('%')
        ?.lowercase(Locale.ROOT)
        ?.trimEnd('.')

private fun isAddressLiteral(host: String): Boolean =
    host.contains(':') || host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))

private fun isLocalAddress(address: InetAddress): Boolean {
    if (
        address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress
    ) {
        return true
    }

    val bytes = address.address
    if (address is Inet6Address && bytes.size == 16) {
        // IPv6 unique-local addresses use fc00::/7. Java's isSiteLocalAddress
        // only covers the deprecated fec0::/10 range.
        if ((bytes[0].toInt() and 0xfe) == 0xfc) return true

        // Treat IPv4-mapped IPv6 literals like their embedded IPv4 address.
        val isIpv4Mapped =
            bytes.take(10).all { it == 0.toByte() } &&
                bytes[10] == 0xff.toByte() &&
                bytes[11] == 0xff.toByte()
        if (isIpv4Mapped) {
            return isLocalAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
    }

    // Carrier-grade NAT space is commonly used by user-managed VPN/LAN overlays.
    return bytes.size == 4 &&
        (bytes[0].toInt() and 0xff) == 100 &&
        (bytes[1].toInt() and 0xc0) == 0x40
}

package com.zenstream.zenstreammobile.data

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LOCAL_SERVER_URL = "http://192.168.1.12:9090"
private const val CUSTOM_DNS_SERVER_URL = "https://zso.example.org"
private const val PUBLIC_DNS_ADDRESS = "8.8.8.8"
private const val PUBLIC_ORCHESTRATOR_URL = "https://orchestrator.example"

class LocalNetworkAccessTest {
    @Test
    fun recognizesPrivateAndLocalAddressForms() {
        listOf(
                "http://10.0.0.12:9090",
                "http://172.20.0.12:9090",
                LOCAL_SERVER_URL,
                "http://169.254.10.2:9090",
                "http://100.100.10.2:9090",
                "http://[fd12::1]:9090",
                "http://[fe80::1]:9090",
                "http://media.localhost:9090",
                "http://zenstream.local:9090",
                "http://media.lan:9090",
                "http://media.internal:9090",
                "http://media.intranet:9090",
                "http://server.home.arpa:9090",
                "http://zenstream:9090",
                "http://localhost:9090",
            )
            .forEach { url ->
                assertTrue("Expected local endpoint: $url", isLocalNetworkServer(url))
            }
    }

    @Test
    fun publicServerAddressesDoNotRequireLocalNetworkPermission() = runBlocking {
        assertFalse(isLocalNetworkServer(PUBLIC_ORCHESTRATOR_URL))
        assertFalse(isLocalNetworkServer("http://$PUBLIC_DNS_ADDRESS:9090"))
        val publicIpv6Server = "http://[2606:4700:4700::1111]:9090"
        assertFalse(
            "A public IPv6 literal is not a local hostname",
            isLocalNetworkServer(publicIpv6Server),
        )
        assertFalse(serverRequiresLocalNetworkPermission(publicIpv6Server, sdkInt = 37))
        assertFalse(serverRequiresLocalNetworkPermission(LOCAL_SERVER_URL, sdkInt = 36))
        assertFalse(
            serverRequiresLocalNetworkPermission(
                PUBLIC_ORCHESTRATOR_URL,
                sdkInt = 37,
                resolveHost = { arrayOf(InetAddress.getByName(PUBLIC_DNS_ADDRESS)) },
            )
        )
        assertTrue(
            canUseServerWithLocalNetworkPermission(
                LocalNetworkPermissionStatus.NOT_REQUIRED,
                permissionGranted = false,
            )
        )
    }

    @Test
    fun api37RequiresPermissionForLocalServerAndBlocksUntilGranted() = runBlocking {
        val serverUrl = LOCAL_SERVER_URL

        assertTrue(serverRequiresLocalNetworkPermission(serverUrl, sdkInt = 37))
        assertFalse(
            canUseServerWithLocalNetworkPermission(
                LocalNetworkPermissionStatus.REQUIRED,
                permissionGranted = false,
            )
        )
        assertTrue(
            canUseServerWithLocalNetworkPermission(
                LocalNetworkPermissionStatus.REQUIRED,
                permissionGranted = true,
            )
        )
    }

    @Test
    fun api37RequestsPermissionForCustomDnsNamesThatResolveLocally() = runBlocking {
        val localAddress = InetAddress.getByName("192.168.1.25")
        val initialStatus = initialLocalNetworkPermissionStatus(CUSTOM_DNS_SERVER_URL, sdkInt = 37)

        assertEquals(LocalNetworkPermissionStatus.RESOLVING, initialStatus)
        assertFalse(canUseServerWithLocalNetworkPermission(initialStatus, permissionGranted = true))

        assertTrue(
            serverRequiresLocalNetworkPermission(
                CUSTOM_DNS_SERVER_URL,
                sdkInt = 37,
                resolveHost = { arrayOf(localAddress) },
            )
        )
        val resolvedStatus = resolvedLocalNetworkPermissionStatus(serverRequiresPermission = true)
        assertFalse(
            canUseServerWithLocalNetworkPermission(resolvedStatus, permissionGranted = false)
        )
        assertTrue(canUseServerWithLocalNetworkPermission(resolvedStatus, permissionGranted = true))
    }

    @Test
    fun publicCustomHostnameRemainsBlockedUntilItsClassificationCompletes() = runBlocking {
        val publicHost = "https://orchestrator.example.org"
        val initialStatus = initialLocalNetworkPermissionStatus(publicHost, sdkInt = 37)

        assertEquals(LocalNetworkPermissionStatus.RESOLVING, initialStatus)
        assertFalse(canUseServerWithLocalNetworkPermission(initialStatus, permissionGranted = true))

        assertFalse(
            serverRequiresLocalNetworkPermission(
                publicHost,
                sdkInt = 37,
                resolveHost = { arrayOf(InetAddress.getByName(PUBLIC_DNS_ADDRESS)) },
            )
        )
        val resolvedStatus = resolvedLocalNetworkPermissionStatus(serverRequiresPermission = false)
        assertEquals(LocalNetworkPermissionStatus.NOT_REQUIRED, resolvedStatus)
        assertTrue(
            canUseServerWithLocalNetworkPermission(resolvedStatus, permissionGranted = false)
        )
        assertEquals(
            LocalNetworkPermissionStatus.NOT_REQUIRED,
            initialLocalNetworkPermissionStatus(publicHost, sdkInt = 36),
        )
    }

    @Test
    fun api37DoesNotRequirePermissionWhenCustomHostnameResolutionFails() = runBlocking {
        assertFalse(
            serverRequiresLocalNetworkPermission(
                CUSTOM_DNS_SERVER_URL,
                sdkInt = 37,
                resolveHost = { throw java.net.UnknownHostException("test DNS failure") },
            )
        )
    }
}

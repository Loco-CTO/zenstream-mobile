package com.zenstream.zenstreammobile.offline

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.*
import org.junit.Test

class ConnectionReachabilityTest {
    @Test
    fun serverErrorResponsesStillProveTheServerIsReachable() {
        for (status in listOf(200, 401, 403, 404, 500, 502, 503)) {
            assertEquals(ServerReachability.REACHABLE, reportedServerReachability(status, null))
        }
    }

    @Test
    fun timeoutsAndInterruptedRequestsDoNotInvalidateReachability() {
        assertNull(reportedServerReachability(null, SocketTimeoutException("slow request")))
        assertNull(reportedServerReachability(null, IOException("Canceled")))
        assertNull(reportedServerReachability(null, IOException("stream was reset")))
    }

    @Test
    fun connectionAndCertificateFailuresRemainDistinct() {
        assertEquals(
            ServerReachability.UNREACHABLE,
            reportedServerReachability(null, ConnectException()),
        )
        assertEquals(
            ServerReachability.UNREACHABLE,
            reportedServerReachability(null, UnknownHostException()),
        )
        assertEquals(
            ServerReachability.CERTIFICATE_ERROR,
            reportedServerReachability(null, SSLHandshakeException("certificate")),
        )
    }

    @Test
    fun anAuthenticatedScreenIsNotReplacedByAutomaticOfflineEntry() {
        assertFalse(shouldOpenOfflineEntry(ConnectionMode.AUTOMATIC, true, true, false))
        assertTrue(shouldOpenOfflineEntry(ConnectionMode.OFFLINE, true, true, false))
    }

    @Test
    fun retainedStartupAndExplicitOnlineRecoveryRemainAvailable() {
        assertTrue(shouldOpenOfflineEntry(ConnectionMode.AUTOMATIC, true, false, false))
        assertFalse(shouldOpenOfflineEntry(ConnectionMode.AUTOMATIC, true, false, true))
        assertFalse(shouldOpenOfflineEntry(ConnectionMode.AUTOMATIC, false, false, false))
    }
}

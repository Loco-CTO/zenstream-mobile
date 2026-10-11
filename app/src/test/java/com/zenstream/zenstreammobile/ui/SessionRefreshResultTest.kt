package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.data.CatalogException
import java.io.IOException
import java.security.GeneralSecurityException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.junit.Assert.assertSame
import org.junit.Test

class SessionRefreshResultTest {
    @Test
    fun expectedStartupRefreshFailuresRemainRecoverable() = runBlocking {
        val failures: List<Exception> =
            listOf(
                CatalogException(503, "server unavailable"),
                IOException("network unavailable"),
                JSONException("invalid response"),
                SecurityException("local network permission revoked"),
                GeneralSecurityException("session key unavailable"),
                IllegalStateException("authentication state unavailable"),
                IllegalArgumentException("invalid configured endpoint"),
            )

        failures.forEach { expected ->
            val result = captureSessionRefreshResult<Nothing> { throw expected }
            assertSame(expected, result.exceptionOrNull())
        }
    }

    @Test
    fun cancellationIsRethrownInsteadOfBecomingARetryableFailure() = runBlocking {
        val expected = CancellationException("refresh cancelled")
        val actual =
            try {
                captureSessionRefreshResult<Nothing> { throw expected }
                null
            } catch (error: CancellationException) {
                error
            }

        assertSame(expected, actual)
    }

    @Test
    fun unexpectedProgrammingFailuresAreNotSwallowed() = runBlocking {
        val expected = UnsupportedOperationException("unexpected implementation failure")
        val actual =
            try {
                captureSessionRefreshResult<Nothing> { throw expected }
                null
            } catch (error: UnsupportedOperationException) {
                error
            }

        assertSame(expected, actual)
    }
}

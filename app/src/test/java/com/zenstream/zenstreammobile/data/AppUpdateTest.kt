package com.zenstream.zenstreammobile.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun startupUpdateCheckTreatsNetworkFailureAsNoUpdate() = runTest {
        val updateSource =
            object : UpdateSource {
                override suspend fun checkForUpdate(): AppUpdate? = throw IOException("offline")
            }

        assertNull(checkForUpdateBestEffort(updateSource))
    }

    @Test
    fun startupUpdateCheckStillPropagatesCancellation() = runTest {
        val cancellation = CancellationException("cancel update check")
        val updateSource =
            object : UpdateSource {
                override suspend fun checkForUpdate(): AppUpdate? = throw cancellation
            }
        val caught =
            try {
                checkForUpdateBestEffort(updateSource)
                null
            } catch (error: CancellationException) {
                error
            }

        assertSame(cancellation, caught)
    }

    @Test
    fun unsuccessfulHttpResponsesDoNotReportNoUpdate() = runTest {
        for (statusCode in listOf(403, 429, 503)) {
            val checker = updateChecker(statusCode, "{}")
            val error = runCatching { checker.checkForUpdate() }.exceptionOrNull()

            assertTrue("HTTP $statusCode should fail the check", error is IOException)
        }
    }

    @Test
    fun successfulHttpResponseReportsWhetherThereIsAnUpdate() = runTest {
        assertEquals("1.2.0", updateChecker(200, releaseJson()).checkForUpdate()?.version)
        assertNull(updateChecker(200, releaseJson(), "1.2.0").checkForUpdate())
    }

    @Test
    fun newerStableReleaseWithTheExpectedApkIsReturned() {
        val update = parseLatestReleaseUpdate(releaseJson(), "1.1.0-main.4")

        assertNotNull(update)
        assertEquals("1.2.0", update?.version)
        assertEquals(
            "https://github.com/Loco-CTO/zenstream-mobile/releases/download/v1.2.0/zenstream-mobile-v1.2.0.apk",
            update?.downloadUrl,
        )
    }

    @Test
    fun equalOrOlderReleasesAreIgnored() {
        assertNull(parseLatestReleaseUpdate(releaseJson(), "1.2.0"))
        assertFalse(isNewerVersion("v1.2.0", "1.3.0"))
        assertTrue(isNewerVersion("1.3.0-main.1", "1.2.9"))
    }

    @Test
    fun releasesWithoutATrustedApkAreIgnored() {
        val body = releaseJson().replace("https://github.com/Loco-CTO", "https://example.com")

        assertNull(parseLatestReleaseUpdate(body, "1.1.0"))
    }

    private fun updateChecker(
        statusCode: Int,
        body: String,
        currentVersion: String = "1.1.0",
    ): GitHubUpdateChecker {
        val client =
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(statusCode)
                        .message("Test response")
                        .body(body.toResponseBody())
                        .build()
                }
                .build()
        return GitHubUpdateChecker(client, currentVersion)
    }

    private fun releaseJson(): String =
        """
        {
          "tag_name": "v1.2.0",
          "html_url": "https://github.com/Loco-CTO/zenstream-mobile/releases/tag/v1.2.0",
          "assets": [
            {
              "name": "zenstream-mobile-v1.2.0.apk",
              "browser_download_url": "https://github.com/Loco-CTO/zenstream-mobile/releases/download/v1.2.0/zenstream-mobile-v1.2.0.apk"
            }
          ]
        }
        """
            .trimIndent()
}

package com.zenstream.zenstreammobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
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

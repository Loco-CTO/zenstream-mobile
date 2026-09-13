package com.zenstream.zenstreammobile.audio

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the byte-range contract expected by Media3 progressive playback. */
@RunWith(AndroidJUnit4::class)
class AudioStreamContractTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun sequentialRangeReadsRequestAdjacentBytes() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "audio/flac")
                .setHeader("Content-Range", "bytes 0-3/8")
                .setBody("ABCD")
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "audio/flac")
                .setHeader("Content-Range", "bytes 4-7/8")
                .setBody("EFGH")
        )

        val dataSource = DefaultHttpDataSource.Factory().createDataSource()
        val uri = Uri.parse(server.url("/audio.flac").toString())
        try {
            assertEquals(4L, dataSource.open(dataSpec(uri, position = 0, length = 4)))
            assertEquals("ABCD", readText(dataSource, 4))
            dataSource.close()

            assertEquals(4L, dataSource.open(dataSpec(uri, position = 4, length = 4)))
            assertEquals("EFGH", readText(dataSource, 4))
        } finally {
            dataSource.close()
        }

        assertEquals("bytes=0-3", server.takeRequest().getHeader("Range"))
        assertEquals("bytes=4-7", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun prematureEofIsObservableBeforeTheRequestedRangeLength() {
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Type", "audio/flac")
                .setHeader("Content-Range", "bytes 0-7/8")
                .setBody("ABC")
        )

        val dataSource = DefaultHttpDataSource.Factory().createDataSource()
        val uri = Uri.parse(server.url("/audio.flac").toString())
        val expectedLength = dataSource.open(dataSpec(uri, position = 0, length = 8))
        try {
            val buffer = ByteArray(expectedLength.toInt())
            var totalRead = 0
            while (true) {
                val read = dataSource.read(buffer, totalRead, buffer.size - totalRead)
                if (read == C.RESULT_END_OF_INPUT) break
                totalRead += read
            }

            assertEquals(8L, expectedLength)
            assertEquals(3, totalRead)
            assertTrue(
                "a short response must remain observable to the progressive reader",
                totalRead < expectedLength,
            )
        } finally {
            dataSource.close()
        }

        val request = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("bytes=0-7", request.getHeader("Range"))
    }

    private fun dataSpec(uri: Uri, position: Long, length: Long): DataSpec =
        DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build()

    private fun readText(dataSource: DataSource, length: Int): String {
        val buffer = ByteArray(length)
        var totalRead = 0
        while (totalRead < buffer.size) {
            val read = dataSource.read(buffer, totalRead, buffer.size - totalRead)
            assertTrue("expected $length bytes, received EOF after $totalRead", read >= 0)
            totalRead += read
        }
        return buffer.decodeToString()
    }
}

package com.zenstream.zenstreammobile.audio

import androidx.media3.exoplayer.DefaultRenderersFactory
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRenderersFactoryTest {
    @Test
    fun audioRendererPolicyPrefersMedia3Extensions() {
        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            audioExtensionRendererMode(),
        )
    }

    @Test
    fun bundledFlacArtifactContainsEverySupportedAbi() {
        val artifact =
            sequenceOf(
                    File("app/libs/media3-decoder-flac-1.11.1.aar"),
                    File("libs/media3-decoder-flac-1.11.1.aar"),
                )
                .firstOrNull(File::isFile)
        assertNotNull("The checked-in Media3 FLAC artifact is missing", artifact)
        ZipFile(requireNotNull(artifact)).use { aar ->
            for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
                assertTrue(
                    "Missing LibFLAC native library for $abi",
                    aar.getEntry("jni/$abi/libflacJNI.so") != null,
                )
            }
        }
    }
}

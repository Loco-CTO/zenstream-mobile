package com.zenstream.zenstreammobile.audio

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3DecoderArtifactsTest {
    @Test
    fun bundledAv1ArtifactIncludesEverySupportedAbi() {
        assertNativeLibraries(
            artifactName = "media3-decoder-av1-1.11.1.aar",
            libraryName = "libdav1dJNI.so",
        )
    }

    @Test
    fun bundledFfmpegArtifactIncludesEverySupportedAbi() {
        assertNativeLibraries(
            artifactName = "media3-decoder-ffmpeg-1.11.1.aar",
            libraryName = "libffmpegJNI.so",
        )
    }

    private fun assertNativeLibraries(artifactName: String, libraryName: String) {
        val artifact =
            sequenceOf(
                    File("app/libs/$artifactName"),
                    File("libs/$artifactName"),
                )
                .firstOrNull(File::isFile)
        assertNotNull("The checked-in Media3 artifact is missing: $artifactName", artifact)
        ZipFile(requireNotNull(artifact)).use { aar ->
            for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")) {
                assertTrue(
                    "Missing $libraryName for $abi in $artifactName",
                    aar.getEntry("jni/$abi/$libraryName") != null,
                )
            }
        }
    }
}

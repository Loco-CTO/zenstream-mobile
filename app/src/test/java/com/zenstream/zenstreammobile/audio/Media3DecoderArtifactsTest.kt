package com.zenstream.zenstreammobile.audio

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3DecoderArtifactsTest {
    @Test
    fun bundledNextlibArtifactIncludesEverySupportedAbi() {
        assertNativeLibraries(
            artifactName = "nextlib-media3ext-1.11.1-0.16.0-zenstream1.aar",
            libraryName = "libmedia3ext.so",
        )
    }

    @Test
    fun bundledNextlibFfmpegUsesPrivateLibraryNames() {
        assertNativeLibraries(
            artifactName = "nextlib-media3ext-1.11.1-0.16.0-zenstream1.aar",
            libraryName = "libavcodec-zenstream-media3.so",
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

package com.zenstream.zenstreammobile.data

import androidx.media3.common.util.UnstableApi
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegLibrary

@androidx.annotation.OptIn(UnstableApi::class)
internal object Media3NativeDecoders {
    // Query the loaded library, not just the version used to compile the JNI bridge.
    val available: Boolean by lazy {
        runCatching {
                FfmpegLibrary.isAvailable() &&
                    FfmpegLibrary.getRuntimeCodecVersion() == ((63 shl 16) or (1 shl 8) or 101)
            }
            .getOrDefault(false)
    }

    fun supports(mimeType: String): Boolean =
        available && runCatching { FfmpegLibrary.supportsFormat(mimeType) }.getOrDefault(false)
}

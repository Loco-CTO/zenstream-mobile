package com.zenstream.zenstreammobile.audio

import androidx.media3.exoplayer.DefaultRenderersFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioRenderersFactoryTest {
    @Test
    fun audioRendererPolicyPrefersMedia3Extensions() {
        assertEquals(
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            audioExtensionRendererMode(),
        )
    }
}

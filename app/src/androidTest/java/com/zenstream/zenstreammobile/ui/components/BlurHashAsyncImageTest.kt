package com.zenstream.zenstreammobile.ui.components

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlurHashAsyncImageTest {
    @Test
    fun decodesAValidBlurHash() {
        assertNotNull(decodeBlurHashBitmap("LEHV6nWB2yk8pyo0adR*.7kCMdnj"))
    }

    @Test
    fun invalidBlurHashIsIgnored() {
        assert(decodeBlurHashBitmap("not-a-blurhash") == null)
    }
}

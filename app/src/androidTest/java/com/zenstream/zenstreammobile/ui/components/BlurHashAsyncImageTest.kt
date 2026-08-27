package com.zenstream.zenstreammobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlurHashAsyncImageTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun decodesAValidBlurHash() {
        assertNotNull(decodeBlurHashBitmap("LEHV6nWB2yk8pyo0adR*.7kCMdnj"))
    }

    @Test
    fun invalidBlurHashIsIgnored() {
        assert(decodeBlurHashBitmap("not-a-blurhash") == null)
    }

    @Test
    fun wideFitPlaceholderCoversTheWholeContainer() {
        composeRule.setContent {
            Box(
                modifier =
                    Modifier.size(200.dp, 100.dp)
                        .background(Color.Black)
                        .testTag("blurhash-container")
            ) {
                BlurHashAsyncImage(
                    model = null,
                    imageKey = null,
                    blurHash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj",
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val image = composeRule.onNodeWithTag("blurhash-container").captureToImage()
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        val centerRow = image.height / 2 * image.width
        assertNotEquals(Color.Black.toArgb(), pixels[centerRow])
        assertNotEquals(Color.Black.toArgb(), pixels[centerRow + image.width - 1])
    }
}

package com.zenstream.zenstreammobile.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainTopBarTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun showsBrandLogoWithoutSettingsAction() {
        composeRule.setContent {
            ZenStreamTheme {
                MainTopBar()
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.app_logo_description))
            .assertIsDisplayed()
        assertTrue(
            composeRule
                .onAllNodesWithContentDescription(context.getString(R.string.settings_description))
                .fetchSemanticsNodes()
                .isEmpty()
        )
        assertTrue(
            composeRule
                .onAllNodesWithContentDescription(context.getString(R.string.search))
                .fetchSemanticsNodes()
                .isEmpty()
        )
    }

    @Test
    fun searchActionInvokesCallbackWhenEnabled() {
        var searchClicked = false
        composeRule.setContent {
            ZenStreamTheme {
                MainTopBar(showSearchAction = true, onSearch = { searchClicked = true })
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.search))
            .assertIsDisplayed()
            .performClick()
        assertTrue(searchClicked)
    }

    @Test
    fun homeToolbarAndStatusBarScrimTransitionWithScroll() {
        val scrolled = mutableStateOf(false)
        composeRule.setContent {
            ZenStreamTheme {
                Box(Modifier.fillMaxSize().background(Color.Blue)) {
                    HomeTopBarSlot(
                        scrolled = scrolled.value,
                        visibilityFraction = 1f,
                        modifier = Modifier.align(Alignment.TopCenter),
                        statusBarInsets = WindowInsets(0, 24, 0, 0),
                    ) {
                        MainTopBar(
                            showSearchAction = true,
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            containerColor = Color.Transparent,
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val transparentImage = composeRule.onNodeWithTag("home_top_bar_slot").captureToImage()
        assertEquals(Color.Blue.toArgb(), centerPixel(transparentImage, 4))
        assertEquals(
            Color.Blue.toArgb(),
            centerPixel(transparentImage, transparentImage.height / 2),
        )

        composeRule.runOnIdle { scrolled.value = true }
        composeRule.waitForIdle()
        val scrolledImage = composeRule.onNodeWithTag("home_top_bar_slot").captureToImage()
        assertEquals(Color.Black.toArgb(), centerPixel(scrolledImage, 4))
        assertEquals(Color.Black.toArgb(), centerPixel(scrolledImage, scrolledImage.height / 2))

        composeRule.runOnIdle { scrolled.value = false }
        composeRule.waitForIdle()
        val returnedImage = composeRule.onNodeWithTag("home_top_bar_slot").captureToImage()
        assertEquals(Color.Blue.toArgb(), centerPixel(returnedImage, 4))
        assertEquals(Color.Blue.toArgb(), centerPixel(returnedImage, returnedImage.height / 2))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.search))
            .assertIsDisplayed()
    }

    @Test
    fun lucideIconResourcesAreAvailable() {
        listOf(
                LucideR.drawable.lucide_ic_house,
                LucideR.drawable.lucide_ic_search,
                LucideR.drawable.lucide_ic_library_big,
                LucideR.drawable.lucide_ic_settings,
                LucideR.drawable.lucide_ic_play,
                LucideR.drawable.lucide_ic_pause,
                LucideR.drawable.lucide_ic_arrow_left,
                LucideR.drawable.lucide_ic_heart,
                LucideR.drawable.lucide_ic_captions,
            )
            .forEach { resourceId ->
                assertTrue("Expected a Lucide drawable resource", resourceId != 0)
            }
    }

    private fun centerPixel(image: ImageBitmap, row: Int): Int {
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        return pixels[row.coerceIn(0, image.height - 1) * image.width + image.width / 2]
    }
}

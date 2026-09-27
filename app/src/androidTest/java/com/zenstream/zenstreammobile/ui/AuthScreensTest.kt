package com.zenstream.zenstreammobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.ui.screens.FEATURE_ARTWORK_MAX_SCREEN_HEIGHT_FRACTION
import com.zenstream.zenstreammobile.ui.screens.FEATURE_ARTWORK_MIN_HEIGHT_DP
import com.zenstream.zenstreammobile.ui.screens.FEATURE_ARTWORK_SCREEN_HEIGHT_FRACTION
import com.zenstream.zenstreammobile.ui.screens.FEATURE_BAR_MAX_SCREEN_HEIGHT_FRACTION
import com.zenstream.zenstreammobile.ui.screens.FEATURE_BAR_MIN_HEIGHT_DP
import com.zenstream.zenstreammobile.ui.screens.FEATURE_BAR_SCREEN_HEIGHT_FRACTION
import com.zenstream.zenstreammobile.ui.screens.FeaturedHero
import com.zenstream.zenstreammobile.ui.screens.ServerSetupScreen
import com.zenstream.zenstreammobile.ui.screens.calculateFeatureArtworkHeight
import com.zenstream.zenstreammobile.ui.screens.calculateFeatureBarHeight
import com.zenstream.zenstreammobile.ui.screens.calculateFeatureBarMaxHeight
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AuthScreensTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun serverSetupShowsMaterialForm() {
        composeRule.setContent { ZenStreamTheme { ServerSetupScreen {} } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule
            .onNodeWithText(context.getString(R.string.server_setup_title))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.orchestrator_url)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.continue_label)).assertIsDisplayed()
    }

    @Test
    fun serverSetupRestoresPreviouslyEnteredAddress() {
        composeRule.setContent {
            ZenStreamTheme {
                ServerSetupScreen(initialServerUrl = "https://orchestrator.example") {}
            }
        }

        composeRule.onNodeWithText("https://orchestrator.example").assertIsDisplayed()
    }

    @Test
    fun featuredHeroShowsLogoMetadataAndDescriptionWithoutActions() {
        val item =
            MediaItem(
                id = "1",
                name = "Example",
                type = "Series",
                productionYear = 2026,
                runtimeTicks = 23L * 600_000_000L,
                overview = "A young hunter discovers a hidden world beneath the mountains.",
                imageTags = mapOf("Logo" to "/api/catalog/items/1/image/logo"),
                backdropImageTags = listOf("backdrop-tag"),
            )
        val session = AuthSession("https://example.com", "token", "user", "name")

        composeRule.setContent {
            ZenStreamTheme {
                FeaturedHero(
                    items = listOf(item),
                    session = session,
                    showEmptyLibrary = false,
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val logoDescription = context.getString(R.string.logo_description, "Example")
        val expectedMetadata = "2026 · Series · ${context.getString(R.string.runtime_minutes, 23)}"
        composeRule
            .onNodeWithContentDescription(logoDescription, useUnmergedTree = true)
            .assertExists()
        composeRule
            .onNodeWithContentDescription(logoDescription, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(expectedMetadata, useUnmergedTree = true).assertIsDisplayed()
        composeRule
            .onNodeWithText(
                "A young hunter discovers a hidden world beneath the mountains.",
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag("featured_hero_play").assertCountEquals(0)
        composeRule.onAllNodesWithTag("featured_hero_favorite").assertCountEquals(0)
        val overviewBounds =
            composeRule
                .onNodeWithText(
                    "A young hunter discovers a hidden world beneath the mountains.",
                    useUnmergedTree = true,
                )
                .getUnclippedBoundsInRoot()
        val indicatorBounds =
            composeRule
                .onNodeWithTag("featured_hero_page_indicators", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        assertTrue(
            "Expected spacing between the synopsis and slide indicators",
            (indicatorBounds.top - overviewBounds.bottom).value >= 16f,
        )
        composeRule
            .onNodeWithTag("featured_hero_page_indicators", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag("featured_hero_page_indicator_0", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Test
    fun featuredHeroFillsTheWidthAndItsIndicatorsStayAboveBottomNavigation() {
        val item =
            MediaItem(
                id = "1",
                name = "Example",
                type = "Movie",
                imageTags = mapOf("Logo" to "logo-tag"),
                backdropImageTags = listOf("backdrop-tag"),
            )
        val session = AuthSession("https://example.com", "token", "user", "name")
        var expectedTopGradientHeightDp = 0f

        composeRule.setContent {
            ZenStreamTheme {
                expectedTopGradientHeightDp =
                    with(LocalDensity.current) {
                        WindowInsets.statusBarsIgnoringVisibility.getTop(this).toDp().value +
                            TopAppBarDefaults.TopAppBarExpandedHeight.value
                    }
                Box(Modifier.fillMaxSize().testTag("featured_hero_test_root")) {
                    Column(Modifier.align(Alignment.TopCenter)) {
                        FeaturedHero(
                            items = listOf(item),
                            session = session,
                            showEmptyLibrary = false,
                        )
                    }
                    Box(
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(80.dp)
                            .background(androidx.compose.ui.graphics.Color.Black)
                            .testTag("featured_test_bottom_navigation")
                    )
                }
            }
        }

        val rootBounds =
            composeRule.onNodeWithTag("featured_hero_test_root").getUnclippedBoundsInRoot()
        val heroBounds = composeRule.onNodeWithTag("featured_hero").getUnclippedBoundsInRoot()
        val artworkBounds =
            composeRule
                .onNodeWithTag("featured_hero_artwork", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        val topGradientBounds =
            composeRule
                .onNodeWithTag("featured_hero_top_gradient", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        val indicatorBounds =
            composeRule
                .onNodeWithTag("featured_hero_page_indicators", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        val bottomNavigationBounds =
            composeRule.onNodeWithTag("featured_test_bottom_navigation").getUnclippedBoundsInRoot()

        assertEquals(rootBounds.left.value, heroBounds.left.value, 0.5f)
        assertEquals(rootBounds.right.value, heroBounds.right.value, 0.5f)
        assertEquals(rootBounds.left.value, artworkBounds.left.value, 0.5f)
        assertEquals(rootBounds.right.value, artworkBounds.right.value, 0.5f)
        assertEquals(rootBounds.top.value, topGradientBounds.top.value, 0.5f)
        assertEquals(
            expectedTopGradientHeightDp,
            (topGradientBounds.bottom - topGradientBounds.top).value,
            1f,
        )
        val configuration =
            InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        val screenHeightDp = configuration.screenHeightDp
        val screenWidthDp = configuration.screenWidthDp
        assertEquals(
            calculateFeatureArtworkHeight(screenHeightDp, screenWidthDp).value,
            (artworkBounds.bottom - artworkBounds.top).value,
            1f,
        )
        assertTrue(
            "Expected hero height to meet the responsive minimum; hero=$heroBounds",
            (heroBounds.bottom - heroBounds.top).value >=
                calculateFeatureBarHeight(screenHeightDp).value - 1f,
        )
        assertTrue(
            "Expected hero height to fit under the responsive maximum; hero=$heroBounds",
            (heroBounds.bottom - heroBounds.top).value <=
                calculateFeatureBarMaxHeight(screenHeightDp).value + 1f,
        )
        assertTrue(
            "Expected indicators above bottom navigation; root=$rootBounds, hero=$heroBounds, " +
                "indicators=$indicatorBounds, bottomNavigation=$bottomNavigationBounds",
            indicatorBounds.bottom < bottomNavigationBounds.top,
        )
    }

    @Test
    fun emptyFeaturedLibraryStillShowsItsEmptyState() {
        val session = AuthSession("https://example.com", "token", "user", "name")

        composeRule.setContent {
            ZenStreamTheme {
                FeaturedHero(
                    items = emptyList(),
                    session = session,
                    showEmptyLibrary = true,
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.empty_library)).assertIsDisplayed()
    }

    @Test
    fun pressingHeroDescriptionOpensItsItem() {
        val item =
            MediaItem(
                id = "movie-1",
                name = "Example Movie",
                type = "Movie",
                overview = "A short description of this movie.",
                imageTags = mapOf("Logo" to "logo-tag"),
                backdropImageTags = listOf("backdrop-tag"),
            )
        val session = AuthSession("https://example.com", "token", "user", "name")
        var opened: MediaItem? = null

        composeRule.setContent {
            ZenStreamTheme {
                FeaturedHero(
                    items = listOf(item),
                    session = session,
                    showEmptyLibrary = false,
                    onItemClick = { opened = it },
                )
            }
        }

        composeRule.onNodeWithText(item.overview!!).performTouchInput { click() }

        assertEquals(item, opened)
    }

    @Test
    fun featureBarUses50To90PercentHeightWithResponsiveArtworkAndLandscapeMinimums() {
        assertEquals(0.5f, FEATURE_BAR_SCREEN_HEIGHT_FRACTION, 0.001f)
        assertEquals(0.9f, FEATURE_BAR_MAX_SCREEN_HEIGHT_FRACTION, 0.001f)
        assertEquals(0.4f, FEATURE_ARTWORK_SCREEN_HEIGHT_FRACTION, 0.001f)
        assertEquals(0.8f, FEATURE_ARTWORK_MAX_SCREEN_HEIGHT_FRACTION, 0.001f)
        assertEquals(400f, calculateFeatureBarHeight(800).value, 0.001f)
        assertEquals(720f, calculateFeatureBarMaxHeight(800).value, 0.001f)
        assertEquals(320f, calculateFeatureArtworkHeight(800, 400).value, 0.001f)
        assertEquals(400f, calculateFeatureArtworkHeight(800, 1200).value, 0.001f)
        assertEquals(640f, calculateFeatureArtworkHeight(800, 3000).value, 0.001f)
        assertEquals(FEATURE_BAR_MIN_HEIGHT_DP, calculateFeatureBarHeight(392).value)
        assertEquals(352.8f, calculateFeatureBarMaxHeight(392).value, 0.1f)
        assertEquals(FEATURE_ARTWORK_MIN_HEIGHT_DP, calculateFeatureArtworkHeight(392, 392).value)
        assertEquals(283.67f, calculateFeatureArtworkHeight(392, 851).value, 0.1f)
    }
}

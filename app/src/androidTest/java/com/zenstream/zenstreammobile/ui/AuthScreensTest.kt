package com.zenstream.zenstreammobile.ui

import android.content.res.Configuration
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.test.swipeLeft
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
import com.zenstream.zenstreammobile.ui.screens.calculateFeatureLogoWidth
import com.zenstream.zenstreammobile.ui.screens.shouldExpandFeatureHero
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val screenWidthDp =
            InstrumentationRegistry.getInstrumentation()
                .targetContext
                .resources
                .configuration
                .screenWidthDp
        composeRule
            .onNodeWithContentDescription(logoDescription, useUnmergedTree = true)
            .assertExists()
        composeRule
            .onNodeWithContentDescription(logoDescription, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(expectedMetadata, useUnmergedTree = true).assertIsDisplayed()
        val logoBounds =
            composeRule
                .onNodeWithContentDescription(logoDescription, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        val metadataBounds =
            composeRule
                .onNodeWithText(expectedMetadata, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        assertEquals(
            "Expected logo to align with metadata",
            metadataBounds.left.value,
            logoBounds.left.value,
            1f,
        )
        assertEquals(
            calculateFeatureLogoWidth(screenWidthDp).value,
            (logoBounds.right - logoBounds.left).value,
            1f,
        )
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
        if (shouldExpandFeatureHero(screenHeightDp, screenWidthDp)) {
            assertEquals(
                calculateFeatureBarMaxHeight(screenHeightDp).value,
                (heroBounds.bottom - heroBounds.top).value,
                1f,
            )
            assertTrue(
                "Expected tablet artwork to expand into available hero height; artwork=$artworkBounds",
                (artworkBounds.bottom - artworkBounds.top).value >=
                    screenHeightDp * FEATURE_ARTWORK_SCREEN_HEIGHT_FRACTION &&
                    (artworkBounds.bottom - artworkBounds.top).value <=
                        screenHeightDp * FEATURE_ARTWORK_MAX_SCREEN_HEIGHT_FRACTION,
            )
        } else {
            assertEquals(
                calculateFeatureArtworkHeight(screenHeightDp, screenWidthDp).value,
                (artworkBounds.bottom - artworkBounds.top).value,
                1f,
            )
        }
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
    fun featureBarUses50To70PercentHeightWithResponsiveArtworkAndLandscapeMinimums() {
        assertEquals(0.5f, FEATURE_BAR_SCREEN_HEIGHT_FRACTION, 0.001f)
        assertEquals(0.7f, FEATURE_BAR_MAX_SCREEN_HEIGHT_FRACTION, 0.001f)
        assertEquals(0.4f, FEATURE_ARTWORK_SCREEN_HEIGHT_FRACTION, 0.001f)
        assertEquals(0.6f, FEATURE_ARTWORK_MAX_SCREEN_HEIGHT_FRACTION, 0.001f)
        assertEquals(400f, calculateFeatureBarHeight(800).value, 0.001f)
        assertEquals(560f, calculateFeatureBarMaxHeight(800).value, 0.001f)
        assertEquals(320f, calculateFeatureArtworkHeight(800, 400).value, 0.001f)
        assertEquals(400f, calculateFeatureArtworkHeight(800, 1200).value, 0.001f)
        assertEquals(480f, calculateFeatureArtworkHeight(800, 3000).value, 0.001f)
        assertEquals(FEATURE_BAR_MIN_HEIGHT_DP, calculateFeatureBarHeight(392).value)
        assertEquals(FEATURE_BAR_MIN_HEIGHT_DP, calculateFeatureBarMaxHeight(392).value)
        assertEquals(FEATURE_ARTWORK_MIN_HEIGHT_DP, calculateFeatureArtworkHeight(392, 392).value)
        assertEquals(235.2f, calculateFeatureArtworkHeight(392, 851).value, 0.1f)
        assertTrue(shouldExpandFeatureHero(800, 1200))
        assertFalse(shouldExpandFeatureHero(1200, 800))
        assertEquals(188f, calculateFeatureLogoWidth(393).value, 0.001f)
        assertEquals(336f, calculateFeatureLogoWidth(800).value, 0.001f)
        assertEquals(440f, calculateFeatureLogoWidth(1200).value, 0.001f)
    }

    @Test
    fun tabletHeroUsesSeventyPercentHeightAndKeepsIndicatorsStableAcrossDescriptionLines() {
        val shortDescriptionItem =
            MediaItem(
                id = "tablet-feature",
                name = "Tablet Feature",
                type = "Movie",
                productionYear = 2026,
                overview = "A featured item with a short description.",
                imageTags = mapOf("Logo" to "logo-tag"),
                backdropImageTags = listOf("backdrop-tag"),
            )
        val longDescriptionItem =
            shortDescriptionItem.copy(
                id = "tablet-feature-long-description",
                overview =
                    "A longer featured item description that wraps to a second line on the " +
                        "available screen width so the slide selector must hold its position.",
            )
        val session = AuthSession("https://example.com", "token", "user", "name")
        val tabletConfiguration =
            Configuration(
                    InstrumentationRegistry.getInstrumentation()
                        .targetContext
                        .resources
                        .configuration
                )
                .apply {
                    screenWidthDp = 1200
                    screenHeightDp = 800
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                }
        val displayConfiguration = mutableStateOf(tabletConfiguration)

        composeRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides displayConfiguration.value) {
                ZenStreamTheme {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.align(Alignment.TopCenter)) {
                            FeaturedHero(
                                items = listOf(shortDescriptionItem, longDescriptionItem),
                                session = session,
                                showEmptyLibrary = false,
                            )
                        }
                    }
                }
            }
        }

        val heroBounds = composeRule.onNodeWithTag("featured_hero").getUnclippedBoundsInRoot()
        val artworkBounds =
            composeRule
                .onNodeWithTag("featured_hero_artwork", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        val indicatorBounds =
            composeRule
                .onNodeWithTag("featured_hero_page_indicators", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()

        assertEquals(560f, (heroBounds.bottom - heroBounds.top).value, 1f)
        assertTrue(
            "Expected tablet artwork to use the available vertical space; artwork=$artworkBounds",
            (artworkBounds.bottom - artworkBounds.top).value >= 440f &&
                (artworkBounds.bottom - artworkBounds.top).value <= 480f,
        )
        assertTrue(
            "Expected slide indicators to stay at the bottom of the expanded hero",
            (heroBounds.bottom - indicatorBounds.bottom).value <= 5f,
        )

        composeRule
            .onNodeWithTag("featured_hero_artwork", useUnmergedTree = true)
            .performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        val nextHeroBounds = composeRule.onNodeWithTag("featured_hero").getUnclippedBoundsInRoot()
        val nextIndicatorBounds =
            composeRule
                .onNodeWithTag("featured_hero_page_indicators", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        assertEquals(
            (heroBounds.bottom - heroBounds.top).value,
            (nextHeroBounds.bottom - nextHeroBounds.top).value,
            1f,
        )
        assertEquals(indicatorBounds.top.value, nextIndicatorBounds.top.value, 1f)

        val portraitTabletConfiguration =
            Configuration(tabletConfiguration).apply {
                screenWidthDp = 800
                screenHeightDp = 1200
                orientation = Configuration.ORIENTATION_PORTRAIT
            }
        composeRule.runOnIdle {
            displayConfiguration.value = portraitTabletConfiguration
        }
        composeRule.waitForIdle()
        val portraitHeroBounds =
            composeRule.onNodeWithTag("featured_hero").getUnclippedBoundsInRoot()
        val portraitArtworkBounds =
            composeRule
                .onNodeWithTag("featured_hero_artwork", useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
        assertEquals(600f, (portraitHeroBounds.bottom - portraitHeroBounds.top).value, 1f)
        assertEquals(480f, (portraitArtworkBounds.bottom - portraitArtworkBounds.top).value, 1f)
    }
}

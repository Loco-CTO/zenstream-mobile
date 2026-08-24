package com.zenstream.zenstreammobile.ui.navigation

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    private val session = AuthSession("https://example.test", "token", "user", "Test")

    @Test
    fun destinationsAreHomeFavoritesLibraryAndMyPageWithoutSearch() {
        assertEquals(
            listOf("home", "favorites", "library", "my-page"),
            mainNavigationDestinations().map { it.route },
        )
        assertTrue(mainNavigationDestinations().none { it.route == "search" })
    }

    @Test
    fun searchActionIsLimitedToMainContentRoutes() {
        assertTrue(shouldShowMainSearchAction("home"))
        assertTrue(shouldShowMainSearchAction("favorites"))
        assertTrue(shouldShowMainSearchAction("library"))
        assertTrue(!shouldShowMainSearchAction("search"))
        assertTrue(!shouldShowMainSearchAction("my-page"))
        assertTrue(!shouldShowMainSearchAction("detail"))
    }

    @Test
    fun releasePageWithoutAnExternalHandlerDoesNotCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertFalse(openReleasePage(context, "zenstream-release-test://page"))
    }

    @Test
    fun releasePageAddsNewTaskFlagForNonActivityContexts() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        var launchedIntent: Intent? = null
        val context =
            object : ContextWrapper(baseContext) {
                override fun startActivity(intent: Intent) {
                    launchedIntent = intent
                }
            }

        assertTrue(
            openReleasePage(
                context,
                "https://github.com/example/zenstream-mobile/releases/tag/v1.2.0",
            )
        )
        val intent = requireNotNull(launchedIntent)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun myPageUsesUsernameInitialWhenAvatarIsUnavailable() {
        composeRule.setContent {
            ZenStreamTheme {
                MainNavigationBar(
                    currentRoute = "home",
                    session = session,
                    onDestinationClick = {},
                )
            }
        }

        composeRule.onNodeWithText("T", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun destinationLabelsRenderInRequestedOrder() {
        composeRule.setContent {
            ZenStreamTheme {
                MainNavigationBar(
                    currentRoute = "home",
                    session = session,
                    onDestinationClick = {},
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val labels =
            listOf(
                    R.string.home,
                    R.string.favorites,
                    R.string.library,
                    R.string.my_page,
                )
                .map { resource ->
                    composeRule
                        .onNodeWithText(context.getString(resource))
                        .getUnclippedBoundsInRoot()
                }

        assertTrue(labels.zipWithNext().all { (left, right) -> left.left < right.left })
    }

    @Test
    fun partiallyCollapsedChromeReleasesItsMatchingLayoutFootprint() {
        val chromeVisibilityFraction = mutableStateOf(1f)

        composeRule.setContent {
            ZenStreamTheme {
                Column(Modifier.fillMaxSize()) {
                    ChromeVisibilitySlot(
                        visibilityFraction = chromeVisibilityFraction.value,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(Modifier.fillMaxWidth().height(104.dp).testTag("chrome-slot-content"))
                    }
                    Box(Modifier.fillMaxWidth().weight(1f).testTag("chrome-following-content"))
                }
            }
        }

        composeRule.waitForIdle()
        composeRule.runOnIdle { chromeVisibilityFraction.value = 0.5f }
        composeRule.waitForIdle()

        val partiallyCollapsedBounds =
            composeRule.onNodeWithTag("chrome-following-content").getUnclippedBoundsInRoot()
        assertEquals(52f, partiallyCollapsedBounds.top.value, 0.5f)

        composeRule.runOnIdle { chromeVisibilityFraction.value = 0f }
        composeRule.waitForIdle()

        val hiddenBounds =
            composeRule.onNodeWithTag("chrome-following-content").getUnclippedBoundsInRoot()
        assertEquals(0f, hiddenBounds.top.value, 0.5f)
    }

    @Test
    fun partiallyCollapsedTopBarKeepsStatusBarInset() {
        val chromeVisibilityFraction = mutableStateOf(0.5f)
        val statusBarInset = 24.dp
        val topBarBodyHeight = 104.dp

        composeRule.setContent {
            ZenStreamTheme {
                val density = LocalDensity.current
                val statusBarInsets =
                    WindowInsets(
                        0,
                        with(density) { statusBarInset.roundToPx() },
                        0,
                        0,
                    )
                Column(Modifier.fillMaxSize()) {
                    StatusBarAwareTopBarSlot(
                        visibilityFraction = chromeVisibilityFraction.value,
                        modifier = Modifier.fillMaxWidth(),
                        statusBarInsets = statusBarInsets,
                    ) {
                        Box(Modifier.fillMaxWidth().height(topBarBodyHeight))
                    }
                    Box(Modifier.fillMaxWidth().weight(1f).testTag("status-bar-following-content"))
                }
            }
        }

        composeRule.waitForIdle()

        val partiallyCollapsedBounds =
            composeRule.onNodeWithTag("status-bar-following-content").getUnclippedBoundsInRoot()
        assertEquals((statusBarInset + 52.dp).value, partiallyCollapsedBounds.top.value, 0.5f)

        composeRule.runOnIdle { chromeVisibilityFraction.value = 0f }
        composeRule.waitForIdle()

        val hiddenBounds =
            composeRule.onNodeWithTag("status-bar-following-content").getUnclippedBoundsInRoot()
        assertEquals(statusBarInset.value, hiddenBounds.top.value, 0.5f)
    }

    @Test
    fun partiallyCollapsedBottomBarKeepsNavigationInsetFixed() {
        val chromeVisibilityFraction = mutableStateOf(1f)
        val bottomBarBodyHeight = 104.dp

        composeRule.setContent {
            ZenStreamTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().weight(1f).testTag("bottom-bar-following-content"))
                    ChromeVisibilitySlot(
                        visibilityFraction = chromeVisibilityFraction.value,
                        modifier = Modifier.fillMaxWidth(),
                        collapseFromBottom = true,
                        applyNavigationBarsPadding = true,
                    ) {
                        Box(Modifier.fillMaxWidth().height(bottomBarBodyHeight))
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val fullyVisibleBounds =
            composeRule.onNodeWithTag("bottom-bar-following-content").getUnclippedBoundsInRoot()

        composeRule.runOnIdle { chromeVisibilityFraction.value = 0.5f }
        composeRule.waitForIdle()

        val partiallyCollapsedBounds =
            composeRule.onNodeWithTag("bottom-bar-following-content").getUnclippedBoundsInRoot()
        assertEquals(
            (bottomBarBodyHeight / 2).value,
            (partiallyCollapsedBounds.bottom - fullyVisibleBounds.bottom).value,
            0.5f,
        )
    }

    @Test
    fun selectingHomeAfterSearchRemovesSearchRoute() {
        composeRule.setContent {
            ZenStreamTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route ?: "home"

                LaunchedEffect(Unit) { navController.navigate("search") }
                Column {
                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") { Text("Home content") }
                        dialog(
                            "search",
                            dialogProperties =
                                DialogProperties(
                                    usePlatformDefaultWidth = false,
                                    decorFitsSystemWindows = false,
                                    dismissOnClickOutside = false,
                                ),
                        ) {
                            Text("Search content")
                        }
                    }
                    MainNavigationBar(
                        currentRoute = currentRoute,
                        session = session,
                        onDestinationClick = { route ->
                            navigateToMainDestination(navController, route)
                        },
                    )
                }
            }
        }

        composeRule.onNodeWithText("Search content").assertIsDisplayed()
        composeRule
            .onNodeWithText(
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.home)
            )
            .performClick()
        composeRule.onNodeWithText("Home content").assertIsDisplayed()
        composeRule.onNodeWithText("Search content").assertDoesNotExist()
    }
}

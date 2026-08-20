package com.zenstream.zenstreammobile.ui.navigation

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun updateLinkWithoutAnExternalHandlerDoesNotCrash() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        assertFalse(openUpdateLink(context, "zenstream-update-test://download"))
    }

    @Test
    fun updateLinkAddsNewTaskFlagForNonActivityContexts() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        var launchedIntent: Intent? = null
        val context =
            object : ContextWrapper(baseContext) {
                override fun startActivity(intent: Intent) {
                    launchedIntent = intent
                }
        }

        assertTrue(openUpdateLink(context, "https://github.com/example/download.apk"))
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

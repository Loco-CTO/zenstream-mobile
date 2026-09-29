package com.zenstream.zenstreammobile.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.SearchDataSource
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.PagedSearch
import com.zenstream.zenstreammobile.ui.screens.SearchOverlayScreen
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Rule
import org.junit.Test

class SearchOverlayNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val session =
        AuthSession(
            "https://example.test",
            "token",
            "search-navigation-${java.util.UUID.randomUUID()}",
            "Test",
        )

    @Test
    fun dialogLeavesHomeFavoritesAndLibraryVisibleUnderSearch() {
        var currentRoute by mutableStateOf("home")
        lateinit var navController: NavHostController
        composeRule.setContent {
            ZenStreamTheme {
                navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") { Text("Home content") }
                    composable("favorites") { Text("Favorites content") }
                    composable("library") { Text("Library content") }
                    dialog(
                        "search",
                        dialogProperties =
                            DialogProperties(
                                usePlatformDefaultWidth = false,
                                decorFitsSystemWindows = false,
                                dismissOnClickOutside = false,
                            ),
                    ) {
                        SearchOverlayScreen(
                            repository = EmptySearchDataSource,
                            session = session,
                            currentRoute = currentRoute,
                            onDestinationClick = {},
                            onDismiss = { navController.popBackStack() },
                            onItemClick = {},
                        )
                    }
                }
            }
        }

        listOf(
                "home" to "Home content",
                "favorites" to "Favorites content",
                "library" to "Library content",
            )
            .forEach { (route, content) ->
                composeRule.runOnIdle {
                    currentRoute = route
                    if (navController.currentDestination?.route != route) {
                        navController.navigate(route)
                    }
                }
                composeRule.waitUntil(5_000) {
                    composeRule.onAllNodesWithText(content).fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.runOnIdle { navController.navigate("search") }
                composeRule.waitUntil(5_000) {
                    composeRule
                        .onAllNodesWithTag("search-dialog")
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
                composeRule.onNodeWithText(content).assertIsDisplayed()
                composeRule
                    .onNodeWithContentDescription(
                        InstrumentationRegistry.getInstrumentation()
                            .targetContext
                            .getString(R.string.back)
                    )
                    .performClick()
                composeRule.waitUntil(5_000) {
                    runCatching {
                        composeRule
                            .onAllNodesWithTag("search-dialog")
                            .fetchSemanticsNodes()
                            .isEmpty()
                    }
                        .getOrDefault(false)
                }
                composeRule.onNodeWithText(content).assertIsDisplayed()
            }
    }

    @Test
    fun backDismissesTheDialogAndRestoresHome() {
        composeRule.setContent {
            ZenStreamTheme {
                val navController = rememberNavController()
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
                        SearchOverlayScreen(
                            repository = EmptySearchDataSource,
                            session = session,
                            currentRoute = "home",
                            onDestinationClick = {},
                            onDismiss = { navController.popBackStack() },
                            onItemClick = {},
                        )
                    }
                }
                LaunchedEffect(Unit) { navController.navigate("search") }
            }
        }

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("search-dialog").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.activity.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(5_000) {
            runCatching {
                composeRule.onAllNodesWithTag("search-dialog").fetchSemanticsNodes().isEmpty()
            }
                .getOrDefault(false)
        }
        composeRule.onNodeWithText("Home content").assertIsDisplayed()
    }
}

private object EmptySearchDataSource : SearchDataSource {
    override suspend fun clearSession() = Unit

    override suspend fun search(session: AuthSession, query: String, page: Int) =
        PagedSearch(emptyList(), 0)
}

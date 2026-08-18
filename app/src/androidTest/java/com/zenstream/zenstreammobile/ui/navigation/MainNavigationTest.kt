package com.zenstream.zenstreammobile.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
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

        composeRule.onNodeWithText("T").assertIsDisplayed()
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
}

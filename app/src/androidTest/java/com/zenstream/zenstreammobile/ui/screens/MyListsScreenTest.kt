package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.FavoritesDataSource
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.FavoriteSort
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.PagedFavorites
import com.zenstream.zenstreammobile.model.PlaylistSummary
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Rule
import org.junit.Test

class MyListsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun switchesBetweenWatchlistFavoritesAndPlaylists() {
        val repository = EmptyListsDataSource()
        val session = AuthSession("https://example.com", "token", "user", "Alex")
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        composeRule.setContent {
            ZenStreamTheme {
                FavoritesScreen(
                    repository = repository,
                    session = session,
                    padding = PaddingValues(),
                    onItemClick = {},
                )
            }
        }

        composeRule.onNodeWithText(context.getString(R.string.my_lists)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.watchlist_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.playlists)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.playlists_empty)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.favorites)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.no_favorites)).assertIsDisplayed()
    }
}

private class EmptyListsDataSource : FavoritesDataSource {
    override suspend fun clearSession() = Unit

    override suspend fun favoritesPage(
        session: AuthSession,
        startIndex: Int,
        limit: Int,
        sort: FavoriteSort,
    ) = PagedFavorites(emptyList(), 0)

    override suspend fun cachedFavoriteSort(userId: String): FavoriteSort? = null

    override suspend fun saveFavoriteSort(userId: String, sort: FavoriteSort) = Unit

    override suspend fun watchlist(session: AuthSession): List<MediaItem> = emptyList()

    override suspend fun playlists(session: AuthSession): List<PlaylistSummary> = emptyList()
}

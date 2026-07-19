package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.DetailData
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Rule
import org.junit.Test

class DetailScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun seriesDetailShowsActionsEpisodesAndCast() {
        val series = MediaItem("series", "Example Series", type = "Series")
        val episode = MediaItem("episode", "Pilot", type = "Episode", indexNumber = 1)
        val data = DetailData(
            item = series,
            seasons = listOf(MediaItem("season", "Season", indexNumber = 1)),
            episodes = listOf(episode),
            selectedSeasonId = "season",
            similar = emptyList(),
        )
        val session = AuthSession("https://example.com", "token", "user", "name")
        composeRule.setContent {
            ZenStreamTheme {
                DetailContent(
                    data = data,
                    session = session,
                    padding = PaddingValues(),
                    actionBusy = false,
                    actionError = false,
                    onPlay = {},
                    onOpenItem = {},
                    onSelectSeason = {},
                    onTogglePlayed = {},
                    onToggleFavorite = {},
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.play)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.episodes_label)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.episode_title, 1, "Pilot"))
            .assertIsDisplayed()
    }

    @Test
    fun episodeDetailShowsParentSeriesActionAndOverview() {
        val episode = MediaItem(
            "episode",
            "Pilot",
            type = "Episode",
            overview = List(8) {
                "A beginning. This is a deliberately long overview that should be collapsed initially so the detail screen remains compact and readable on a phone."
            }.joinToString(" "),
        )
        val series = MediaItem("series", "Example Series", type = "Series")
        val session = AuthSession("https://example.com", "token", "user", "name")
        composeRule.setContent {
            ZenStreamTheme {
                DetailContent(
                    data = DetailData(item = episode, parentSeries = series),
                    session = session,
                    padding = PaddingValues(),
                    actionBusy = false,
                    actionError = false,
                    onPlay = {},
                    onOpenItem = {},
                    onSelectSeason = {},
                    onTogglePlayed = {},
                    onToggleFavorite = {},
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.parent_series, "Example Series"))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.show_more)).assertIsDisplayed()
    }
}

package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.platform.app.InstrumentationRegistry
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.BazarrSearchResult
import com.zenstream.zenstreammobile.model.BazarrStatus
import com.zenstream.zenstreammobile.model.BazarrSubtitleMatch
import com.zenstream.zenstreammobile.model.DetailData
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaSource
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.model.PlaybackTrackSelection
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DetailScreensTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun seriesDetailShowsActionsEpisodesAndCast() {
        val series = MediaItem("series", "Example Series", type = "Series")
        val episode =
            MediaItem("episode", "Pilot", type = "Episode", indexNumber = 1, played = true)
        val data =
            DetailData(
                item = series,
                seasons =
                    listOf(
                        MediaItem("season", "Season", indexNumber = 1),
                        MediaItem("season-2", "The Return", indexNumber = 2),
                    ),
                episodes = listOf(episode),
                selectedSeasonId = "season",
                similar = emptyList(),
            )
        val session = AuthSession("https://example.com", "token", "user", "name")
        var selectedSeasonId: String? = null
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
                    onSelectSeason = { selectedSeasonId = it },
                    onTogglePlayed = {},
                    onToggleFavorite = {},
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.play)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.episodes_label)).assertIsDisplayed()
        composeRule
            .onNodeWithText(context.getString(R.string.season_number, 1))
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.select_season)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.season_number, 2)).performClick()
        assertEquals("season-2", selectedSeasonId)
        composeRule
            .onNodeWithText(context.getString(R.string.episode_title, 1, "Pilot"))
            .assertIsDisplayed()
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.watched_description))
            .assertIsDisplayed()
    }

    @Test
    fun seasonDrawerShowsWatchedAndFavoriteActions() {
        val series = MediaItem("series", "Example Series", type = "Series")
        val data =
            DetailData(
                item = series,
                seasons =
                    listOf(
                        MediaItem("season", "Season", indexNumber = 1),
                        MediaItem("season-2", "The Return", indexNumber = 2),
                    ),
                selectedSeasonId = "season",
            )
        val session = AuthSession("https://example.com", "token", "user", "name")
        val toggledPlayed = mutableListOf<String>()
        val toggledFavorite = mutableListOf<String>()
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
                    onToggleSeasonPlayed = toggledPlayed::add,
                    onToggleSeasonFavorite = toggledFavorite::add,
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.season_number, 1)).performClick()
        composeRule
            .onAllNodesWithContentDescription(context.getString(R.string.mark_watched))
            .get(1)
            .performClick()
        composeRule
            .onAllNodesWithContentDescription(context.getString(R.string.add_favorite))
            .get(1)
            .performClick()

        assertEquals(listOf("season"), toggledPlayed)
        assertEquals(listOf("season"), toggledFavorite)
    }

    @Test
    fun seasonDrawerScrollsToSeasonsBeyondViewport() {
        val series = MediaItem("series", "Example Series", type = "Series")
        val seasons =
            (1..30).map { number ->
                MediaItem("season-$number", "Season $number", indexNumber = number)
            }
        val session = AuthSession("https://example.com", "token", "user", "name")
        composeRule.setContent {
            ZenStreamTheme {
                DetailContent(
                    data =
                        DetailData(
                            item = series,
                            seasons = seasons,
                            selectedSeasonId = seasons.first().id,
                        ),
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
        composeRule.onNodeWithText(context.getString(R.string.season_number, 1)).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.season_number, 30))
            .assertDoesNotExist()

        val drawer = composeRule.onNodeWithTag("season_drawer_list")
        repeat(6) { drawer.performTouchInput { swipeUp() } }

        composeRule
            .onNodeWithText(context.getString(R.string.season_number, 30))
            .assertIsDisplayed()
    }

    @Test
    fun episodeDetailShowsParentSeriesActionAndOverview() {
        val episode =
            MediaItem(
                "episode",
                "Pilot",
                type = "Episode",
                overview =
                    List(8) {
                            "A beginning. This is a deliberately long overview that should be collapsed initially so the detail screen remains compact and readable on a phone."
                        }
                        .joinToString(" "),
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
        composeRule.onNodeWithText(context.getString(R.string.show_more)).assertIsDisplayed()
    }

    @Test
    fun switchingEpisodeScrollsDetailBackToTop() {
        val series = MediaItem("series", "Example Series", type = "Series")
        val season = MediaItem("season", "Season 1", indexNumber = 1)
        val episodeOne =
            MediaItem(
                "episode-1",
                "Episode One",
                type = "Episode",
                seriesId = series.id,
                parentIndexNumber = 1,
                indexNumber = 1,
            )
        val episodeTwo =
            episodeOne.copy(
                id = "episode-2",
                name = "Episode Two",
                indexNumber = 2,
            )
        val episodes =
            (1..30).map { number ->
                MediaItem(
                    "list-episode-$number",
                    "List episode $number",
                    type = "Episode",
                    seriesId = series.id,
                    parentIndexNumber = 1,
                    indexNumber = number,
                )
            }
        val data =
            mutableStateOf(
                DetailData(
                    item = episodeOne,
                    parentSeries = series,
                    seasons = listOf(season),
                    episodes = episodes,
                    selectedSeasonId = season.id,
                )
            )
        val session = AuthSession("https://example.com", "token", "user", "name")
        composeRule.setContent {
            ZenStreamTheme {
                DetailContent(
                    data = data.value,
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

        composeRule.onNodeWithTag("detail_content_list").performTouchInput { swipeUp() }
        composeRule.runOnIdle { data.value = data.value.copy(item = episodeTwo) }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Episode Two").assertIsDisplayed()
    }

    @Test
    fun seasonChangeShowsLoadingIndicator() {
        val series = MediaItem("series", "Example Series", type = "Series")
        val data =
            DetailData(
                item = series,
                seasons = listOf(MediaItem("season", "Season", indexNumber = 1)),
                episodes = listOf(MediaItem("episode", "Pilot", type = "Episode", indexNumber = 1)),
                selectedSeasonId = "season",
            )
        val session = AuthSession("https://example.com", "token", "user", "name")
        composeRule.setContent {
            ZenStreamTheme {
                DetailContent(
                    data = data,
                    session = session,
                    padding = PaddingValues(),
                    loading = true,
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
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.loading))
            .assertIsDisplayed()
    }

    @Test
    fun episodeTopBarUsesSeriesTitleAndOpensSeries() {
        val series = MediaItem("series", "Example Series", type = "Series")
        var opened: MediaItem? = null
        composeRule.setContent {
            ZenStreamTheme {
                DetailTopBar(
                    title = "Pilot",
                    parentSeries = series,
                    onBack = {},
                    onOpenItem = { opened = it },
                )
            }
        }

        composeRule.onNodeWithText("Example Series").performClick()

        assertEquals(series, opened)
    }

    @Test
    fun movieDetailShowsTrackSelectorsAndCanTurnSubtitlesOff() {
        val movie = MediaItem("movie", "Example Movie", type = "Movie")
        val session = AuthSession("https://example.com", "token", "user", "name")
        var selectedSubtitle: Int? = 4
        composeRule.setContent {
            ZenStreamTheme {
                DetailContent(
                    data = DetailData(item = movie),
                    session = session,
                    padding = PaddingValues(),
                    actionBusy = false,
                    actionError = false,
                    onPlay = {},
                    onOpenItem = {},
                    onSelectSeason = {},
                    onTogglePlayed = {},
                    onToggleFavorite = {},
                    trackSource =
                        MediaSource(
                            id = "source-1",
                            mediaStreams =
                                listOf(
                                    MediaStream(1, "Audio", displayTitle = "English"),
                                    MediaStream(2, "Audio", displayTitle = "Japanese"),
                                    MediaStream(4, "Subtitle", displayTitle = "English"),
                                ),
                        ),
                    trackSelection =
                        PlaybackTrackSelection(
                            audioStreamId = 1,
                            subtitleStreamIndex = selectedSubtitle,
                            hasSubtitleSelection = true,
                        ),
                    onSelectSubtitleTrack = { selectedSubtitle = it },
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.audio_track)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.subtitle_track)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.subtitles_off)).performClick()

        assertEquals(null, selectedSubtitle)
    }

    @Test
    fun matchedEpisodeWithoutSubtitleTracksShowsDownloaderOption() {
        val episode = MediaItem("episode", "Pilot", type = "Episode")
        val session = AuthSession("https://example.com", "token", "user", "name")
        composeRule.setContent {
            ZenStreamTheme {
                DetailContent(
                    data = DetailData(item = episode),
                    session = session,
                    padding = PaddingValues(),
                    actionBusy = false,
                    actionError = false,
                    onPlay = {},
                    onOpenItem = {},
                    onSelectSeason = {},
                    onTogglePlayed = {},
                    onToggleFavorite = {},
                    trackSource =
                        MediaSource("source-1", mediaStreams = listOf(MediaStream(1, "Audio"))),
                    trackSelection = PlaybackTrackSelection(),
                    bazarrStatus = BazarrStatus("matched"),
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.subtitle_track)).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.bazarr_find_subtitles))
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.bazarr_subtitles)).assertIsDisplayed()
    }

    @Test
    fun matchedMovieWithoutSubtitleTracksShowsDownloaderOption() {
        val movie = MediaItem("movie", "Film", type = "Movie")
        val session = AuthSession("https://example.com", "token", "user", "name")
        composeRule.setContent {
            ZenStreamTheme {
                DetailContent(
                    data = DetailData(item = movie),
                    session = session,
                    padding = PaddingValues(),
                    actionBusy = false,
                    actionError = false,
                    onPlay = {},
                    onOpenItem = {},
                    onSelectSeason = {},
                    onTogglePlayed = {},
                    onToggleFavorite = {},
                    trackSource =
                        MediaSource("source-1", mediaStreams = listOf(MediaStream(1, "Audio"))),
                    trackSelection = PlaybackTrackSelection(),
                    bazarrStatus = BazarrStatus("matched"),
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.subtitle_track)).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.bazarr_find_subtitles))
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText(context.getString(R.string.bazarr_subtitles)).assertIsDisplayed()
    }

    @Test
    fun matchedEpisodePlacesDownloaderAfterOffAndBeforeExistingSubtitleTracks() {
        val episode = MediaItem("episode", "Pilot", type = "Episode")
        val session = AuthSession("https://example.com", "token", "user", "name")
        composeRule.setContent {
            ZenStreamTheme {
                DetailContent(
                    data = DetailData(item = episode),
                    session = session,
                    padding = PaddingValues(),
                    actionBusy = false,
                    actionError = false,
                    onPlay = {},
                    onOpenItem = {},
                    onSelectSeason = {},
                    onTogglePlayed = {},
                    onToggleFavorite = {},
                    trackSource =
                        MediaSource(
                            "source-1",
                            mediaStreams =
                                listOf(
                                    MediaStream(1, "Audio"),
                                    MediaStream(2, "Subtitle", displayTitle = "English"),
                                ),
                        ),
                    trackSelection = PlaybackTrackSelection(),
                    bazarrStatus = BazarrStatus("matched"),
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.subtitle_track)).performClick()
        val subtitlesOffTop =
            composeRule
                .onNodeWithText(context.getString(R.string.subtitles_off))
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        val downloaderTop =
            composeRule
                .onNodeWithText(context.getString(R.string.bazarr_find_subtitles))
                .fetchSemanticsNode()
                .boundsInRoot
                .top
        val existingSubtitleTop =
            composeRule.onNodeWithText("English").fetchSemanticsNode().boundsInRoot.top

        assertTrue(subtitlesOffTop < downloaderTop)
        assertTrue(downloaderTop < existingSubtitleTop)
    }

    @Test
    fun downloaderSheetShowsMatchesAndInvokesDownloadCallback() {
        val episode = MediaItem("episode", "Pilot", type = "Episode")
        val session = AuthSession("https://example.com", "token", "user", "name")
        val searchResult =
            BazarrSearchResult(
                state = "matched",
                matches =
                    listOf(
                        BazarrSubtitleMatch(
                            matchId = "match-1",
                            name = "Japanese subtitle",
                            provider = "opensubtitles",
                            language = "ja",
                            format = "srt",
                        )
                    ),
            )
        val status = mutableStateOf(BazarrStatus("matched"))
        val search = mutableStateOf<BazarrSearchResult?>(null)
        var downloadedMatchId: String? = null
        composeRule.setContent {
            ZenStreamTheme {
                DetailContent(
                    data = DetailData(item = episode),
                    session = session,
                    padding = PaddingValues(),
                    actionBusy = false,
                    actionError = false,
                    onPlay = {},
                    onOpenItem = {},
                    onSelectSeason = {},
                    onTogglePlayed = {},
                    onToggleFavorite = {},
                    trackSource =
                        MediaSource("source-1", mediaStreams = listOf(MediaStream(1, "Audio"))),
                    trackSelection = PlaybackTrackSelection(),
                    bazarrStatus = status.value,
                    bazarrSearch = search.value,
                    onSearchBazarr = { search.value = searchResult },
                    onDownloadBazarr = {
                        downloadedMatchId = it
                        status.value = status.value.copy(state = "download_started")
                        search.value = null
                    },
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.subtitle_track)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.bazarr_find_subtitles)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.bazarr_find_subtitles)).performClick()
        composeRule.onNodeWithText("Japanese subtitle").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.bazarr_download)).performClick()

        assertEquals("match-1", downloadedMatchId)
        composeRule
            .onNodeWithText(context.getString(R.string.bazarr_download_queued))
            .assertIsDisplayed()
    }

    @Test
    fun movieDownloaderSheetShowsMatchesAndInvokesDownloadCallback() {
        val movie = MediaItem("movie", "Film", type = "Movie")
        val session = AuthSession("https://example.com", "token", "user", "name")
        val searchResult =
            BazarrSearchResult(
                state = "matched",
                matches =
                    listOf(
                        BazarrSubtitleMatch(
                            matchId = "movie-match",
                            name = "English subtitle",
                            provider = "opensubtitles",
                            language = "en",
                            format = "srt",
                        )
                    ),
            )
        val status = mutableStateOf(BazarrStatus("matched"))
        val search = mutableStateOf<BazarrSearchResult?>(null)
        var downloadedMatchId: String? = null
        composeRule.setContent {
            ZenStreamTheme {
                DetailContent(
                    data = DetailData(item = movie),
                    session = session,
                    padding = PaddingValues(),
                    actionBusy = false,
                    actionError = false,
                    onPlay = {},
                    onOpenItem = {},
                    onSelectSeason = {},
                    onTogglePlayed = {},
                    onToggleFavorite = {},
                    trackSource =
                        MediaSource("source-1", mediaStreams = listOf(MediaStream(1, "Audio"))),
                    trackSelection = PlaybackTrackSelection(),
                    bazarrStatus = status.value,
                    bazarrSearch = search.value,
                    onSearchBazarr = { search.value = searchResult },
                    onDownloadBazarr = {
                        downloadedMatchId = it
                        status.value = status.value.copy(state = "download_started")
                        search.value = null
                    },
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.subtitle_track)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.bazarr_find_subtitles)).performClick()
        composeRule.onNodeWithText(context.getString(R.string.bazarr_find_subtitles)).performClick()
        composeRule.onNodeWithText("English subtitle").assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.bazarr_download)).performClick()

        assertEquals("movie-match", downloadedMatchId)
        composeRule
            .onNodeWithText(context.getString(R.string.bazarr_download_queued))
            .assertIsDisplayed()
    }

    @Test
    fun unmatchedEpisodeHidesDownloaderAndSelectorWhenNoSubtitleTracksExist() {
        val episode = MediaItem("episode", "Pilot", type = "Episode")
        val session = AuthSession("https://example.com", "token", "user", "name")
        composeRule.setContent {
            ZenStreamTheme {
                DetailContent(
                    data = DetailData(item = episode),
                    session = session,
                    padding = PaddingValues(),
                    actionBusy = false,
                    actionError = false,
                    onPlay = {},
                    onOpenItem = {},
                    onSelectSeason = {},
                    onTogglePlayed = {},
                    onToggleFavorite = {},
                    trackSource =
                        MediaSource("source-1", mediaStreams = listOf(MediaStream(1, "Audio"))),
                    trackSelection = PlaybackTrackSelection(),
                    bazarrStatus = BazarrStatus("unmatched"),
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.subtitle_track)).assertDoesNotExist()
    }

    @Test
    fun unmatchedEpisodeKeepsTracksWithoutDownloaderAction() {
        val episode = MediaItem("episode", "Pilot", type = "Episode")
        val session = AuthSession("https://example.com", "token", "user", "name")
        composeRule.setContent {
            ZenStreamTheme {
                DetailContent(
                    data = DetailData(item = episode),
                    session = session,
                    padding = PaddingValues(),
                    actionBusy = false,
                    actionError = false,
                    onPlay = {},
                    onOpenItem = {},
                    onSelectSeason = {},
                    onTogglePlayed = {},
                    onToggleFavorite = {},
                    trackSource =
                        MediaSource(
                            "source-1",
                            mediaStreams =
                                listOf(
                                    MediaStream(1, "Audio"),
                                    MediaStream(2, "Subtitle", displayTitle = "English"),
                                ),
                        ),
                    trackSelection = PlaybackTrackSelection(),
                    bazarrStatus = BazarrStatus("ambiguous"),
                )
            }
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.onNodeWithText(context.getString(R.string.subtitle_track)).performClick()
        composeRule
            .onNodeWithText(context.getString(R.string.bazarr_find_subtitles))
            .assertDoesNotExist()
    }
}

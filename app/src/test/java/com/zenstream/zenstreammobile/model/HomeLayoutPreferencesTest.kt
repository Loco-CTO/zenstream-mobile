package com.zenstream.zenstreammobile.model

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutPreferencesTest {
    private fun row(title: RowTitle, name: String, libraryId: String? = null) =
        MediaRow(
            title = title,
            libraryName = name.takeIf { libraryId != null },
            items = listOf(MediaItem(name, name)),
            label = name.takeIf { title == RowTitle.Genre },
            libraryId = libraryId,
        )

    @Test
    fun defaultOrderIsPreservedAndLibraryAndGenreRowsHaveStableSeparateIds() {
        val rows =
            listOf(
                row(RowTitle.Genre, "Comedy"),
                row(RowTitle.NewlyAdded, "Movies A", "library-a"),
                row(RowTitle.ContinueWatching, "Continue"),
                row(RowTitle.NewlyAdded, "Movies B", "library-b"),
                row(RowTitle.Genre, "Drama"),
            )

        assertEquals(
            listOf(
                "home:continueWatching",
                "home:library:library-a:newlyAddedOn",
                "home:library:library-b:newlyAddedOn",
                "home:genre:comedy",
                "home:genre:drama",
            ),
            orderHomeRows(rows, HomeLayoutPreferences()).map(::homeSectionId),
        )
    }

    @Test
    fun customOrderAndVisibilityApplyToEachRowIndependently() {
        val rows =
            listOf(
                row(RowTitle.ContinueWatching, "Continue"),
                row(RowTitle.NewlyAdded, "Movies A", "library-a"),
                row(RowTitle.NewlyAdded, "Movies B", "library-b"),
                row(RowTitle.Genre, "Comedy"),
                row(RowTitle.Genre, "Drama"),
            )
        val preferences =
            HomeLayoutPreferences(
                order =
                    listOf(
                        "home:genre:drama",
                        "home:library:library-b:newlyAddedOn",
                        "home:genre:comedy",
                    ),
                hidden = listOf("home:genre:drama", "home:library:library-a:newlyAddedOn"),
            )

        assertEquals(
            listOf(
                "home:library:library-b:newlyAddedOn",
                "home:genre:comedy",
                "home:continueWatching",
            ),
            orderHomeRows(rows, preferences).map(::homeSectionId),
        )
        assertEquals(
            listOf(
                "home:genre:drama",
                "home:library:library-b:newlyAddedOn",
                "home:genre:comedy",
                "home:continueWatching",
                "home:library:library-a:newlyAddedOn",
            ),
            orderHomeRows(rows, preferences, includeHidden = true).map(::homeSectionId),
        )
    }

    @Test
    fun newlyAvailableRowsAreVisibleAndUnavailableSettingsSurviveReorder() {
        val availableIds = listOf("home:continueWatching", "home:genre:comedy", "home:genre:drama")
        val preferences =
            HomeLayoutPreferences(
                order =
                    listOf(
                        "home:genre:comedy",
                        "home:genre:temporarily-missing",
                        "home:continueWatching",
                    ),
                hidden = listOf("home:genre:temporarily-missing"),
            )
        val reordered =
            reorderHomeLayoutPreferences(
                preferences,
                availableIds,
                listOf("home:genre:drama", "home:genre:comedy", "home:continueWatching"),
            )

        assertEquals(
            listOf(
                "home:genre:drama",
                "home:genre:temporarily-missing",
                "home:genre:comedy",
                "home:continueWatching",
            ),
            reordered.order,
        )
        assertEquals(preferences.hidden, reordered.hidden)
        assertEquals(
            listOf("home:genre:drama", "home:genre:comedy", "home:continueWatching"),
            orderHomeRows(
                    listOf(
                        row(RowTitle.ContinueWatching, "Continue"),
                        row(RowTitle.Genre, "Comedy"),
                        row(RowTitle.Genre, "Drama"),
                    ),
                    reordered,
                )
                .map(::homeSectionId),
        )
    }
}

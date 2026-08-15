package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.model.DetailData
import com.zenstream.zenstreammobile.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailViewModelTest {
    @Test
    fun refreshKeepsTheCurrentlySelectedSeason() {
        val refreshed =
            DetailData(
                item = MediaItem("series", "Example Series", type = "Series"),
                seasons =
                    listOf(
                        MediaItem("season-1", "Season 1", indexNumber = 1),
                        MediaItem("season-2", "Season 2", indexNumber = 2),
                    ),
                selectedSeasonId = "season-1",
            )

        assertEquals("season-2", keepSelectedSeason(refreshed, "season-2").selectedSeasonId)
    }

    @Test
    fun unavailableRequestedSeasonFallsBackToTheServerSelectionOrSeasonOne() {
        val refreshed =
            DetailData(
                item = MediaItem("series", "Example Series", type = "Series"),
                seasons =
                    listOf(
                        MediaItem("specials", "Specials", indexNumber = 0),
                        MediaItem("season-1", "Season 1", indexNumber = 1),
                    ),
                selectedSeasonId = "missing-season",
            )

        assertEquals("season-1", keepSelectedSeason(refreshed, "removed-season").selectedSeasonId)
    }
}

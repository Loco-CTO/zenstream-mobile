package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.ui.components.episodeCardSubtitle
import com.zenstream.zenstreammobile.ui.components.episodeCardTitle
import com.zenstream.zenstreammobile.ui.components.progressPercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaHelpersTest {
    @Test
    fun progressPrefersServerPercentage() {
        assertEquals(42, progressPercent(MediaItem("1", "Movie", playedPercentage = 42.4)))
    }

    @Test
    fun progressUsesTicksWhenPercentageMissing() {
        assertEquals(
            50,
            progressPercent(MediaItem("1", "Movie", runtimeTicks = 100, playbackPositionTicks = 50))
        )
    }

    @Test
    fun progressIsAbsentWhenPlaybackHasNotStarted() {
        assertNull(progressPercent(MediaItem("1", "Movie", playedPercentage = 0.0)))
        assertNull(
            progressPercent(MediaItem("1", "Movie", runtimeTicks = 100, playbackPositionTicks = 0))
        )
    }

    @Test
    fun episodeCardMatchesWebSeriesAndEpisodeLabel() {
        val episode = MediaItem(
            id = "episode-1",
            name = "The Episode",
            type = "Episode",
            seriesName = "The Series",
            seriesId = "series-1",
            parentIndexNumber = 2,
            indexNumber = 7,
        )

        assertEquals("The Series", episodeCardTitle(episode))
        assertEquals("S02E07 · The Episode", episodeCardSubtitle(episode))
    }
}

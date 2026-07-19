package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailHelpersTest {
    @Test
    fun seriesPlaybackUsesFirstUnwatchedEpisode() {
        val series = MediaItem("s", "Series", type = "Series")
        val episodes = listOf(
            MediaItem("e1", "First", type = "Episode", played = true),
            MediaItem("e2", "Second", type = "Episode"),
        )
        assertEquals("e2", detailPlaybackTarget(series, episodes).id)
    }

    @Test
    fun seriesPlaybackFallsBackToFirstEpisodeOrSeries() {
        val series = MediaItem("s", "Series", type = "Series")
        val watched = listOf(MediaItem("e1", "First", type = "Episode", played = true))
        assertEquals("e1", detailPlaybackTarget(series, watched).id)
        assertEquals("s", detailPlaybackTarget(series, emptyList()).id)
    }
}

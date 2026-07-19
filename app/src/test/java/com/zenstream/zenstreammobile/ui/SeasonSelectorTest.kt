package com.zenstream.zenstreammobile.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SeasonSelectorTest {
    @Test
    fun seasonChipUsesNumberedFormat() {
        assertEquals("Season 1", seasonChipLabel(1, "Season 1", "Season %1\$d"))
    }

    @Test
    fun seasonChipFallsBackToNameWhenSeasonHasNoNumber() {
        assertEquals("Specials", seasonChipLabel(null, "Specials", "Season %1\$d"))
    }
}

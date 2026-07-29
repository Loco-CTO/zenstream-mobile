package com.zenstream.zenstreammobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackActivityContractTest {
    @Test
    fun parsesRequiredItemIdAndOptionalName() {
        assertEquals(
            PlaybackLaunchArgs("item-1", "Example"),
            parsePlaybackLaunchArgs("item-1", "Example"),
        )
        assertEquals(
            PlaybackLaunchArgs("item-1", ""),
            parsePlaybackLaunchArgs("item-1", null),
        )
    }

    @Test
    fun rejectsMissingItemId() {
        assertNull(parsePlaybackLaunchArgs(null, "Example"))
        assertNull(parsePlaybackLaunchArgs("  ", "Example"))
    }

    @Test
    fun preservesTrackSelectionAndExplicitSubtitleOff() {
        assertEquals(
            PlaybackLaunchArgs(
                "item-1",
                "Example",
                audioStreamId = 3,
                subtitleStreamIndex = null,
                hasSubtitleSelection = true,
            ),
            parsePlaybackLaunchArgs(
                "item-1",
                "Example",
                audioStreamId = 3,
                hasSubtitleSelection = true,
            ),
        )
    }
}

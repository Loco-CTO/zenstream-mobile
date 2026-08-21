package com.zenstream.zenstreammobile

import com.zenstream.zenstreammobile.model.PlaybackOptions
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.ui.PlaybackUiState
import com.zenstream.zenstreammobile.ui.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerEngineDefaultsTest {
    @Test
    fun playbackDefaultsToMpv() {
        assertEquals(PlayerEngine.MPV, PlaybackOptions().engine)
        assertEquals(PlayerEngine.MPV, PlaybackUiState().engineType)
        assertEquals(PlayerEngine.MPV, SettingsUiState().playerEngine)
    }
}

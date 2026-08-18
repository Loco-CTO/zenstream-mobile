package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.data.InterfaceLocaleMode
import com.zenstream.zenstreammobile.data.InterfaceLocalePreference
import com.zenstream.zenstreammobile.data.MetadataPreference
import com.zenstream.zenstreammobile.data.PlaybackPreference
import com.zenstream.zenstreammobile.data.SettingsDataSource
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SubtitleStyle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun failedPreferenceSavesRollbackToTheLastConfirmedValues() = runTest {
        val source = FakeSettingsDataSource()
        val viewModel = SettingsViewModel(source)
        advanceUntilIdle()
        source.localeSave = { error("locale failed") }
        source.metadataSave = { error("metadata failed") }

        viewModel.setInterfaceLocaleMode(InterfaceLocaleMode.Japanese)
        advanceUntilIdle()
        assertEquals(InterfaceLocaleMode.Automatic, viewModel.uiState.value.interfaceLocaleMode)
        assertTrue(viewModel.uiState.value.interfaceLocaleSaveError)

        viewModel.setMetadataLanguage("ja")
        advanceUntilIdle()
        assertEquals(null, viewModel.uiState.value.metadataLanguage)
        assertTrue(viewModel.uiState.value.metadataSaveError)
    }

    @Test
    fun localeSavesAreSerializedAndOnlyTheLatestResultUpdatesState() = runTest {
        val firstSave = CompletableDeferred<Unit>()
        val requests = mutableListOf<InterfaceLocaleMode>()
        val source = FakeSettingsDataSource()
        source.localeSave = { mode ->
            requests += mode
            if (mode == InterfaceLocaleMode.English) firstSave.await()
            source.interfaceLocaleMode.value = mode
            InterfaceLocalePreference(mode, mode.storageValue, source.metadataPreference)
        }
        val viewModel = SettingsViewModel(source)
        advanceUntilIdle()

        viewModel.setInterfaceLocaleMode(InterfaceLocaleMode.English)
        runCurrent()
        viewModel.setInterfaceLocaleMode(InterfaceLocaleMode.Japanese)
        runCurrent()
        assertTrue(viewModel.uiState.value.interfaceLocaleSaving)

        firstSave.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf(InterfaceLocaleMode.English, InterfaceLocaleMode.Japanese),
            requests,
        )
        assertEquals(InterfaceLocaleMode.Japanese, viewModel.uiState.value.interfaceLocaleMode)
        assertFalse(viewModel.uiState.value.interfaceLocaleSaving)
    }

    @Test
    fun localeRefreshCannotOverwriteANewerMetadataSave() = runTest {
        val localeSave = CompletableDeferred<Unit>()
        val source = FakeSettingsDataSource()
        source.localeSave = { mode ->
            localeSave.await()
            source.interfaceLocaleMode.value = mode
            InterfaceLocalePreference(
                mode,
                "ja",
                MetadataPreference(listOf("en", "ja"), null, "en"),
            )
        }
        val viewModel = SettingsViewModel(source)
        advanceUntilIdle()

        viewModel.setInterfaceLocaleMode(InterfaceLocaleMode.Japanese)
        runCurrent()
        viewModel.setMetadataLanguage("ja")
        runCurrent()
        assertEquals("ja", viewModel.uiState.value.metadataLanguage)

        localeSave.complete(Unit)
        advanceUntilIdle()

        assertEquals("ja", viewModel.uiState.value.metadataLanguage)
        assertEquals("ja", viewModel.uiState.value.effectiveMetadataLanguage)
        assertFalse(viewModel.uiState.value.metadataSaving)
    }

    @Test
    fun subtitleBottomSpacingUpdatesAndUsesTheExistingSavePath() = runTest {
        val saved = mutableListOf<SubtitleStyle>()
        val source =
            FakeSettingsDataSource().also {
                it.subtitleSave = { style ->
                    saved += style
                    style
                }
            }
        val viewModel = SettingsViewModel(source)
        advanceUntilIdle()

        viewModel.updateSubtitle { copy(bottomSpacing = 217f) }
        advanceUntilIdle()

        assertEquals(217f, viewModel.uiState.value.subtitleStyle.bottomSpacing)
        assertEquals(217f, saved.single().bottomSpacing)
    }
}

private class FakeSettingsDataSource : SettingsDataSource {
    override val interfaceLocaleMode = MutableStateFlow(InterfaceLocaleMode.Automatic)
    override val playerEngine = MutableStateFlow(PlayerEngine.MEDIA3)
    override val showDebugIcon = MutableStateFlow(false)
    var metadataPreference = MetadataPreference(listOf("en", "ja"), null, "en")
    var localeSave: suspend (InterfaceLocaleMode) -> InterfaceLocalePreference = { mode ->
        interfaceLocaleMode.value = mode
        InterfaceLocalePreference(mode, mode.storageValue, metadataPreference)
    }
    var metadataSave: suspend (String?) -> MetadataPreference = { language ->
        metadataPreference = MetadataPreference(listOf("en", "ja"), language, language ?: "en")
        metadataPreference
    }
    var subtitleSave: suspend (SubtitleStyle) -> SubtitleStyle = { it }
    var playbackPreference = PlaybackPreference(null, null, emptyList(), emptyList())

    override suspend fun loadMetadataPreference() = metadataPreference

    override suspend fun saveMetadataPreference(language: String?) = metadataSave(language)

    override suspend fun saveInterfaceLocaleMode(mode: InterfaceLocaleMode) = localeSave(mode)

    override suspend fun savePlayerEngine(engine: PlayerEngine) {
        playerEngine.value = engine
    }

    override suspend fun saveShowDebugIcon(enabled: Boolean) {
        showDebugIcon.value = enabled
    }

    override suspend fun loadSubtitleStyle() = SubtitleStyle()

    override suspend fun saveSubtitleStyle(style: SubtitleStyle) = subtitleSave(style)

    override suspend fun loadPlaybackPreference() = playbackPreference

    override suspend fun savePlaybackPreference(
        audioLanguage: String?,
        subtitleLanguage: String?,
    ): PlaybackPreference {
        playbackPreference = playbackPreference.copy(audioLanguage, subtitleLanguage)
        return playbackPreference
    }
}

package com.zenstream.zenstreammobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.InterfaceLocaleMode
import com.zenstream.zenstreammobile.data.PlaybackPreference
import com.zenstream.zenstreammobile.data.SettingsDataSource
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SubtitleStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SettingsUiState(
    val interfaceLocaleMode: InterfaceLocaleMode = InterfaceLocaleMode.Automatic,
    val interfaceLocaleSaving: Boolean = false,
    val interfaceLocaleSaveError: Boolean = false,
    val playerEngine: PlayerEngine = PlayerEngine.MEDIA3,
    val showDebugIcon: Boolean = false,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val subtitleSaveError: Boolean = false,
    val refreshing: Boolean = false,
    val metadataLanguages: List<String> = emptyList(),
    val metadataLanguage: String? = null,
    val effectiveMetadataLanguage: String = "en",
    val metadataSaving: Boolean = false,
    val metadataSaveError: Boolean = false,
    val playbackPreference: PlaybackPreference = PlaybackPreference(null, null, emptyList(), emptyList()),
    val playbackSaving: Boolean = false,
    val playbackSaveError: Boolean = false,
    )

class SettingsViewModel(private val repository: SettingsDataSource) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val interfaceLocaleSaveMutex = Mutex()
    private val metadataSaveMutex = Mutex()
    private val playbackSaveMutex = Mutex()
    private var interfaceLocaleSaveGeneration = 0L
    private var metadataSaveGeneration = 0L
    private var confirmedInterfaceLocaleMode = InterfaceLocaleMode.Automatic
    private var confirmedMetadataLanguage: String? = null
    private var confirmedPlaybackPreference = _uiState.value.playbackPreference

    init {
        viewModelScope.launch {
            repository.interfaceLocaleMode.collectLatest { mode ->
                confirmedInterfaceLocaleMode = mode
                if (!_uiState.value.interfaceLocaleSaving) {
                    _uiState.value = _uiState.value.copy(interfaceLocaleMode = mode)
                }
            }
        }
        viewModelScope.launch {
            repository.playerEngine.collectLatest { engine ->
                _uiState.value = _uiState.value.copy(playerEngine = engine)
            }
        }
        viewModelScope.launch {
            repository.showDebugIcon.collectLatest { enabled ->
                _uiState.value = _uiState.value.copy(showDebugIcon = enabled)
            }
        }
        viewModelScope.launch { refreshSettings() }
    }

    fun refresh() {
        viewModelScope.launch { refreshSettings() }
    }

    private suspend fun refreshSettings() {
        _uiState.value = _uiState.value.copy(refreshing = true)
        refreshSubtitleStyle()
        runCatching { repository.loadPlaybackPreference() }
            .onSuccess {
                confirmedPlaybackPreference = it
                _uiState.value = _uiState.value.copy(playbackPreference = it)
            }
        val generation = metadataSaveGeneration
        metadataSaveMutex.withLock {
            runCatching { repository.loadMetadataPreference() }
                .onSuccess {
                    confirmedMetadataLanguage = it.explicitLanguage
                    if (generation == metadataSaveGeneration) {
                        _uiState.value =
                            _uiState.value.copy(
                                metadataLanguages = it.languages,
                                metadataLanguage = it.explicitLanguage,
                                effectiveMetadataLanguage = it.effectiveLanguage,
                            )
                    }
                }
        }
        _uiState.value = _uiState.value.copy(refreshing = false)
    }

    private suspend fun refreshSubtitleStyle() {
        runCatching { repository.loadSubtitleStyle() }
            .onSuccess { _uiState.value = _uiState.value.copy(subtitleStyle = it) }
    }

    fun setPlayerEngine(engine: PlayerEngine) {
        viewModelScope.launch { repository.savePlayerEngine(engine) }
    }

    fun setShowDebugIcon(enabled: Boolean) {
        viewModelScope.launch { repository.saveShowDebugIcon(enabled) }
    }

    fun updateSubtitle(change: SubtitleStyle.() -> SubtitleStyle) {
        val next = change(_uiState.value.subtitleStyle)
        _uiState.value = _uiState.value.copy(subtitleStyle = next, subtitleSaveError = false)
        viewModelScope.launch {
            runCatching { repository.saveSubtitleStyle(next) }
                .onFailure { _uiState.value = _uiState.value.copy(subtitleSaveError = true) }
        }
    }

    fun setPlaybackPreference(
        audioLanguage: String?,
        subtitleLanguage: String?,
    ) {
        val next =
            _uiState.value.playbackPreference.copy(
                audioLanguage = audioLanguage,
                subtitleLanguage = subtitleLanguage,
            )
        _uiState.value =
            _uiState.value.copy(playbackPreference = next, playbackSaving = true, playbackSaveError = false)
        viewModelScope.launch {
            playbackSaveMutex.withLock {
                runCatching {
                    repository.savePlaybackPreference(audioLanguage, subtitleLanguage)
                }
                    .onSuccess {
                        confirmedPlaybackPreference = it
                        _uiState.value =
                            _uiState.value.copy(
                                playbackPreference = it,
                                playbackSaving = false,
                                playbackSaveError = false,
                            )
                    }
                    .onFailure {
                        _uiState.value =
                            _uiState.value.copy(
                                playbackPreference = confirmedPlaybackPreference,
                                playbackSaving = false,
                                playbackSaveError = true,
                            )
                    }
            }
        }
    }

    fun setInterfaceLocaleMode(mode: InterfaceLocaleMode) {
        val generation = ++interfaceLocaleSaveGeneration
        val metadataGeneration = metadataSaveGeneration
        _uiState.value =
            _uiState.value.copy(
                interfaceLocaleMode = mode,
                interfaceLocaleSaving = true,
                interfaceLocaleSaveError = false,
            )
        viewModelScope.launch {
            interfaceLocaleSaveMutex.withLock {
                if (generation != interfaceLocaleSaveGeneration) return@withLock
                runCatching { repository.saveInterfaceLocaleMode(mode) }
                    .onSuccess { result ->
                        confirmedInterfaceLocaleMode = result.mode
                        if (generation != interfaceLocaleSaveGeneration) return@onSuccess
                        val metadata = result.metadataPreference
                        if (metadata != null && metadataGeneration == metadataSaveGeneration) {
                            confirmedMetadataLanguage = metadata.explicitLanguage
                        }
                        val localeState =
                            _uiState.value.copy(
                                interfaceLocaleMode = result.mode,
                                interfaceLocaleSaving = false,
                                interfaceLocaleSaveError = false,
                            )
                        _uiState.value =
                            if (metadata != null && metadataGeneration == metadataSaveGeneration) {
                                localeState.copy(
                                    metadataLanguages = metadata.languages,
                                    metadataLanguage = metadata.explicitLanguage,
                                    effectiveMetadataLanguage = metadata.effectiveLanguage,
                                )
                            } else {
                                localeState
                            }
                    }
                    .onFailure {
                        if (generation != interfaceLocaleSaveGeneration) return@onFailure
                        _uiState.value =
                            _uiState.value.copy(
                                interfaceLocaleMode = confirmedInterfaceLocaleMode,
                                interfaceLocaleSaving = false,
                                interfaceLocaleSaveError = true,
                            )
                    }
            }
        }
    }

    fun setMetadataLanguage(language: String?) {
        val generation = ++metadataSaveGeneration
        _uiState.value =
            _uiState.value.copy(
                metadataLanguage = language,
                metadataSaving = true,
                metadataSaveError = false,
            )
        viewModelScope.launch {
            metadataSaveMutex.withLock {
                if (generation != metadataSaveGeneration) return@withLock
                runCatching { repository.saveMetadataPreference(language) }
                    .onSuccess {
                        confirmedMetadataLanguage = it.explicitLanguage
                        if (generation != metadataSaveGeneration) return@onSuccess
                        _uiState.value =
                            _uiState.value.copy(
                                metadataLanguage = it.explicitLanguage,
                                effectiveMetadataLanguage = it.effectiveLanguage,
                                metadataSaving = false,
                                metadataSaveError = false,
                            )
                    }
                    .onFailure {
                        if (generation != metadataSaveGeneration) return@onFailure
                        _uiState.value =
                            _uiState.value.copy(
                                metadataLanguage = confirmedMetadataLanguage,
                                metadataSaving = false,
                                metadataSaveError = true,
                            )
                    }
            }
        }
    }

    class Factory(private val repository: CatalogRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repository) as T
    }
}

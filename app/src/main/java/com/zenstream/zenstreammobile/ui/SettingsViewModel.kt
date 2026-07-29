package com.zenstream.zenstreammobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SubtitleStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class SettingsUiState(
    val playerEngine: PlayerEngine = PlayerEngine.MEDIA3,
    val showDebugIcon: Boolean = false,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val subtitleSaveError: Boolean = false,
    val refreshing: Boolean = false,
	val metadataLanguages: List<String> = emptyList(),
	val metadataLanguage: String? = null,
	val effectiveMetadataLanguage: String = "en",
	val metadataSaveError: Boolean = false,
)

class SettingsViewModel(
    private val repository: CatalogRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
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
        viewModelScope.launch {
			refreshSettings()
        }
    }

    fun refresh() {
		viewModelScope.launch { refreshSettings() }
    }

	private suspend fun refreshSettings() {
		refreshSubtitleStyle()
		runCatching { repository.loadMetadataPreference() }.onSuccess {
			_uiState.value = _uiState.value.copy(
				metadataLanguages = it.languages,
				metadataLanguage = it.explicitLanguage,
				effectiveMetadataLanguage = it.effectiveLanguage,
			)
		}
	}

    private suspend fun refreshSubtitleStyle() {
        _uiState.value = _uiState.value.copy(refreshing = true)
        runCatching { repository.loadSubtitleStyle() }
            .onSuccess {
                _uiState.value = _uiState.value.copy(
                    subtitleStyle = it,
                    refreshing = false,
                )
            }
            .onFailure {
                _uiState.value = _uiState.value.copy(refreshing = false)
            }
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

	fun setMetadataLanguage(language: String?) {
		_uiState.value = _uiState.value.copy(metadataLanguage = language, metadataSaveError = false)
		viewModelScope.launch {
			runCatching { repository.saveMetadataPreference(language) }
				.onSuccess { _uiState.value = _uiState.value.copy(metadataLanguage = it.explicitLanguage, effectiveMetadataLanguage = it.effectiveLanguage) }
				.onFailure { _uiState.value = _uiState.value.copy(metadataSaveError = true) }
		}
	}

    class Factory(
        private val repository: CatalogRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repository) as T
    }
}

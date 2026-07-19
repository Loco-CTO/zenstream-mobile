package com.zenstream.zenstreammobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zenstream.zenstreammobile.data.JellyfinRepository
import com.zenstream.zenstreammobile.model.PlayerEngine
import com.zenstream.zenstreammobile.model.SubtitleStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class SettingsUiState(
    val playerEngine: PlayerEngine = PlayerEngine.MEDIA3,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val subtitleSaveError: Boolean = false,
    val refreshing: Boolean = false,
)

class SettingsViewModel(
    private val repository: JellyfinRepository,
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
            refreshSubtitleStyle()
        }
    }

    fun refresh() {
        viewModelScope.launch { refreshSubtitleStyle() }
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

    fun updateSubtitle(change: SubtitleStyle.() -> SubtitleStyle) {
        val next = change(_uiState.value.subtitleStyle)
        _uiState.value = _uiState.value.copy(subtitleStyle = next, subtitleSaveError = false)
        viewModelScope.launch {
            runCatching { repository.saveSubtitleStyle(next) }
                .onFailure { _uiState.value = _uiState.value.copy(subtitleSaveError = true) }
        }
    }

    class Factory(
        private val repository: JellyfinRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SettingsViewModel(repository) as T
    }
}

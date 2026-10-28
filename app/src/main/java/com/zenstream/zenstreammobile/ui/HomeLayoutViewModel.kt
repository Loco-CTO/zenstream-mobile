package com.zenstream.zenstreammobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.HomeLayoutDataSource
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.HomeLayoutPreferences
import com.zenstream.zenstreammobile.model.MediaRow
import com.zenstream.zenstreammobile.model.reorderHomeLayoutPreferences
import com.zenstream.zenstreammobile.model.setHomeSectionHidden
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeLayoutUiState(
    val loading: Boolean = false,
    val rows: List<MediaRow> = emptyList(),
    val preferences: HomeLayoutPreferences = HomeLayoutPreferences(),
    val error: Boolean = false,
    val saveError: Boolean = false,
)

class HomeLayoutViewModel(
    private val repository: HomeLayoutDataSource,
    private val session: AuthSession,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeLayoutUiState())
    val uiState = _uiState.asStateFlow()
    private var loaded = false

    init {
        viewModelScope.launch {
            repository.homeLayoutPreferences(session).collectLatest { preferences ->
                _uiState.update { it.copy(preferences = preferences) }
            }
        }
    }

    fun load(force: Boolean = false) {
        if (_uiState.value.loading || (!force && loaded)) return
        _uiState.update { it.copy(loading = true, error = false) }
        viewModelScope.launch {
            try {
                val rows = repository.homeLayoutRows(session)
                loaded = true
                _uiState.update { it.copy(loading = false, rows = rows, error = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _uiState.update { it.copy(loading = false, error = true) }
            }
        }
    }

    fun setHidden(id: String, hidden: Boolean) {
        savePreferences(setHomeSectionHidden(_uiState.value.preferences, id, hidden))
    }

    fun reorder(availableIds: List<String>, reorderedIds: List<String>) {
        savePreferences(
            reorderHomeLayoutPreferences(
                _uiState.value.preferences,
                availableIds,
                reorderedIds,
            )
        )
    }

    private fun savePreferences(preferences: HomeLayoutPreferences) {
        _uiState.update { it.copy(preferences = preferences, saveError = false) }
        viewModelScope.launch {
            try {
                repository.saveHomeLayoutPreferences(session, preferences)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _uiState.update { it.copy(saveError = true) }
            }
        }
    }

    class Factory(
        private val repository: CatalogRepository,
        private val session: AuthSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeLayoutViewModel(repository, session) as T
    }
}

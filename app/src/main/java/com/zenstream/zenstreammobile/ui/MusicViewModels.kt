package com.zenstream.zenstreammobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zenstream.zenstreammobile.data.CatalogException
import com.zenstream.zenstreammobile.data.MusicDataSource
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MusicAlbumData
import com.zenstream.zenstreammobile.model.MusicArtistData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MusicAlbumUiState(
    val loading: Boolean = true,
    val data: MusicAlbumData? = null,
    val error: Boolean = false,
    val actionBusy: Boolean = false,
    val actionError: Boolean = false,
)

class MusicAlbumViewModel(
    private val repository: MusicDataSource,
    private val session: AuthSession,
    private val albumId: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MusicAlbumUiState())
    val uiState = _uiState.asStateFlow()
    private var generation = 0L
    private var loadJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            repository.catalogRefreshRevision.drop(1).collectLatest { load() }
        }
    }

    fun load() {
        loadJob?.cancel()
        val requestedGeneration = ++generation
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = false) }
            try {
                val data = repository.musicAlbum(session, albumId)
                if (requestedGeneration == generation) {
                    _uiState.value = MusicAlbumUiState(loading = false, data = data)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (requestedGeneration != generation) return@launch
                if ((error as? CatalogException)?.statusCode == 401) {
                    repository.clearSessionIfCurrent(session)
                }
                _uiState.update { it.copy(loading = false, error = true) }
            }
        }
    }

    fun toggleFavorite() {
        val current = _uiState.value.data ?: return
        val previous = current.album
        toggleFavorite(previous.id) { result ->
            _uiState.update { state ->
                state.copy(data = state.data?.copy(album = result))
            }
        }
    }

    fun toggleTrackFavorite(trackId: String) {
        val current = _uiState.value.data ?: return
        val previous = current.tracks.firstOrNull { it.id == trackId } ?: return
        toggleFavorite(previous.id) { result ->
            _uiState.update { state ->
                state.copy(
                    data =
                        state.data?.copy(
                            tracks = state.data.tracks.map { if (it.id == result.id) result else it }
                        )
                )
            }
        }
    }

    private fun toggleFavorite(itemId: String, applyResult: (MediaItem) -> Unit) {
        val current = _uiState.value.data ?: return
        val previous =
            when {
                current.album.id == itemId -> current.album
                else -> current.tracks.firstOrNull { it.id == itemId } ?: return
            }
        val optimistic = previous.copy(favorite = !previous.favorite)
        applyResult(optimistic)
        viewModelScope.launch {
            _uiState.update { it.copy(actionBusy = true, actionError = false) }
            try {
                repository.setFavorite(session, itemId, optimistic.favorite)
                _uiState.update { it.copy(actionBusy = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if ((error as? CatalogException)?.statusCode == 401) {
                    repository.clearSessionIfCurrent(session)
                }
                applyResult(previous)
                _uiState.update { it.copy(actionBusy = false, actionError = true) }
            }
        }
    }

    class Factory(
        private val repository: MusicDataSource,
        private val session: AuthSession,
        private val albumId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MusicAlbumViewModel(repository, session, albumId) as T
    }
}

data class MusicArtistUiState(
    val loading: Boolean = true,
    val data: MusicArtistData? = null,
    val error: Boolean = false,
    val tracksLoading: Boolean = false,
    val tracksLoaded: Boolean = false,
    val tracksError: Boolean = false,
    val actionBusy: Boolean = false,
    val actionError: Boolean = false,
)

class MusicArtistViewModel(
    private val repository: MusicDataSource,
    private val session: AuthSession,
    private val artistId: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(MusicArtistUiState())
    val uiState = _uiState.asStateFlow()
    private var generation = 0L
    private var loadJob: Job? = null
    private var tracksJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            repository.catalogRefreshRevision.drop(1).collectLatest { load() }
        }
    }

    fun load() {
        loadJob?.cancel()
        tracksJob?.cancel()
        val requestedGeneration = ++generation
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = false) }
            try {
                val data = repository.musicArtist(session, artistId)
                if (requestedGeneration == generation) {
                    _uiState.value = MusicArtistUiState(loading = false, data = data)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (requestedGeneration != generation) return@launch
                if ((error as? CatalogException)?.statusCode == 401) {
                    repository.clearSessionIfCurrent(session)
                }
                _uiState.update { it.copy(loading = false, error = true) }
            }
        }
    }

    fun loadTracks(onLoaded: (List<MediaItem>) -> Unit = {}) {
        val current = _uiState.value
        if (current.tracksLoaded && current.data != null) {
            onLoaded(current.data.tracks)
            return
        }
        if (current.tracksLoading) return
        val requestedGeneration = generation
        tracksJob?.cancel()
        tracksJob = viewModelScope.launch {
            _uiState.update { it.copy(tracksLoading = true, tracksError = false) }
            try {
                val tracks = repository.musicArtistTracks(session, artistId)
                if (requestedGeneration != generation) return@launch
                val unique = tracks.distinctBy { it.id }
                _uiState.update {
                    it.copy(
                        data = it.data?.copy(tracks = unique, trackCount = maxOf(it.data.trackCount, unique.size)),
                        tracksLoading = false,
                        tracksLoaded = true,
                    )
                }
                onLoaded(unique)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (requestedGeneration != generation) return@launch
                if ((error as? CatalogException)?.statusCode == 401) {
                    repository.clearSessionIfCurrent(session)
                }
                _uiState.update { it.copy(tracksLoading = false, tracksError = true) }
            }
        }
    }

    fun playAll(onLoaded: (List<MediaItem>) -> Unit) = loadTracks(onLoaded)

    fun toggleFollowing() {
        val current = _uiState.value.data ?: return
        val previous = current.artist
        val target = !(previous.following ?: false)
        _uiState.update {
            it.copy(
                data = current.copy(artist = previous.copy(following = target)),
                actionBusy = true,
                actionError = false,
            )
        }
        viewModelScope.launch {
            try {
                repository.setFollowing(session, previous.id, target)
                _uiState.update { it.copy(actionBusy = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if ((error as? CatalogException)?.statusCode == 401) {
                    repository.clearSessionIfCurrent(session)
                }
                _uiState.update {
                    it.copy(
                        data = it.data?.copy(artist = previous),
                        actionBusy = false,
                        actionError = true,
                    )
                }
            }
        }
    }

    fun toggleFavorite() {
        val current = _uiState.value.data ?: return
        val previous = current.artist
        val optimistic = previous.copy(favorite = !previous.favorite)
        _uiState.update { it.copy(data = current.copy(artist = optimistic), actionBusy = true) }
        viewModelScope.launch {
            try {
                repository.setFavorite(session, previous.id, optimistic.favorite)
                _uiState.update { it.copy(actionBusy = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if ((error as? CatalogException)?.statusCode == 401) {
                    repository.clearSessionIfCurrent(session)
                }
                _uiState.update {
                    it.copy(data = it.data?.copy(artist = previous), actionBusy = false, actionError = true)
                }
            }
        }
    }

    class Factory(
        private val repository: MusicDataSource,
        private val session: AuthSession,
        private val artistId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MusicArtistViewModel(repository, session, artistId) as T
    }
}

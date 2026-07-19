package com.zenstream.zenstreammobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zenstream.zenstreammobile.data.HomeDataSource
import com.zenstream.zenstreammobile.data.JellyfinException
import com.zenstream.zenstreammobile.data.JellyfinRepository
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.DetailData
import com.zenstream.zenstreammobile.model.HomeData
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibraryData
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaRow
import com.zenstream.zenstreammobile.model.RowTitle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class AppUiState(
    val loading: Boolean = true,
    val orchestratorUrl: String? = null,
    val serverUrl: String? = null,
    val session: AuthSession? = null,
    val locale: String = com.zenstream.zenstreammobile.data.ENGLISH_LOCALE,
) {
    val showSetup get() = !loading && (orchestratorUrl.isNullOrBlank() || serverUrl.isNullOrBlank())
    val showLogin get() = !loading && !showSetup && session == null
    val showMain get() = !loading && !showSetup && session != null
}

class AppViewModel(private val repository: JellyfinRepository) : ViewModel() {
    val uiState: StateFlow<AppUiState> = combine(
        repository.orchestratorUrl,
        repository.serverUrl,
        repository.session,
        repository.locale,
    ) { orchestrator, server, session, locale ->
        AppUiState(
            loading = false,
            orchestratorUrl = orchestrator,
            serverUrl = server,
            session = session,
            locale = locale,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    init {
        viewModelScope.launch {
            combine(repository.orchestratorUrl, repository.session) { orchestrator, session ->
                orchestrator to session
            }.collectLatest { (orchestrator, session) ->
                if (!orchestrator.isNullOrBlank() && session != null) {
                    runCatching { repository.refreshLocale(orchestrator, session.token) }
                }
            }
        }
    }

    suspend fun configureServer(value: String) = repository.configureOrchestrator(value)
    fun logout() = viewModelScope.launch { repository.clearSession() }
    fun changeServer() = viewModelScope.launch { repository.clearAll() }

    class Factory(private val repository: JellyfinRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(repository) as T
    }
}

data class LoginUiState(
    val username: String = "",
    val busy: Boolean = false,
    val error: String? = null
)

class LoginViewModel(private val repository: JellyfinRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState = _uiState.asStateFlow()

    fun updateUsername(value: String) {
        _uiState.value = _uiState.value.copy(username = value, error = null)
    }

    fun login(password: String) {
        val username = _uiState.value.username.trim()
        if (username.isBlank() || password.isBlank() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, error = null)
            runCatching { repository.authenticate(username, password) }
                .onFailure { _uiState.value = _uiState.value.copy(error = it.message ?: "login") }
            _uiState.value = _uiState.value.copy(busy = false)
        }
    }

    class Factory(private val repository: JellyfinRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LoginViewModel(repository) as T
    }
}

data class HomeUiState(
    val loading: Boolean = true,
    val data: HomeData? = null,
    val error: Boolean = false,
    val pendingSections: Int = 0,
    val successfulSections: Int = 0,
)

class HomeViewModel(private val repository: HomeDataSource, private val session: AuthSession) :
    ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = _uiState.asStateFlow()
    private var loadingJob: Job? = null

    private companion object {
        const val INITIAL_SECTION_COUNT = 4
    }

    init {
        load()
    }

    fun load() {
        if (loadingJob?.isActive == true) return
        if (!_uiState.value.loading && _uiState.value.data != null) return
        _uiState.value = HomeUiState(loading = true, pendingSections = INITIAL_SECTION_COUNT)
        loadingJob = viewModelScope.launch {
            supervisorScope {
                launch {
                    loadSection(
                        request = { repository.homeFeatured(session) },
                        apply = { data, items -> data.copy(featured = items) },
                    )
                }
                launch {
                    loadSection(
                        request = { repository.homeContinueWatching(session) },
                        apply = { data, items -> data.withRow(RowTitle.ContinueWatching, items, wide = true) },
                    )
                }
                launch {
                    loadSection(
                        request = { repository.homeNextUp(session) },
                        apply = { data, items -> data.withRow(RowTitle.NextUp, items, wide = true) },
                    )
                }
                launch { loadLibraries() }
            }
        }
    }

    private suspend fun CoroutineScope.loadLibraries() {
        try {
            val libraries = repository.homeLibraries(session)
            if (libraries.isNotEmpty()) addPendingSections(libraries.size)
            completeSection(success = true)
            if (libraries.isEmpty()) return

            val libraryResults = libraries.map { library ->
                launch {
                    loadSection(
                        request = { repository.homeLibraryData(session, library) },
                        apply = { data, libraryData -> data.withLibraryData(libraries, libraryData) },
                    )
                }
            }
            libraryResults.joinAll()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            handleFailure(error)
            completeSection(success = false)
        }
    }

    private suspend fun <T> loadSection(
        request: suspend () -> T,
        apply: (HomeData, T) -> HomeData,
    ) {
        try {
            val result = request()
            _uiState.update { state ->
                state.copy(
                    data = apply(state.data ?: HomeData(), result),
                )
            }
            completeSection(success = true)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            handleFailure(error)
            completeSection(success = false)
        }
    }

    private suspend fun handleFailure(error: Throwable) {
        if ((error as? JellyfinException)?.statusCode == 401) repository.clearSession()
    }

    private fun addPendingSections(count: Int) {
        _uiState.update { it.copy(pendingSections = it.pendingSections + count) }
    }

    private fun completeSection(success: Boolean) {
        _uiState.update { state ->
            val pending = (state.pendingSections - 1).coerceAtLeast(0)
            val successful = state.successfulSections + if (success) 1 else 0
            state.copy(
                loading = pending > 0,
                error = pending == 0 && successful == 0,
                pendingSections = pending,
                successfulSections = successful,
            )
        }
    }

    class Factory(private val repository: JellyfinRepository, private val session: AuthSession) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(repository, session) as T
    }
}

private fun HomeData.withRow(title: RowTitle, items: List<MediaItem>, wide: Boolean): HomeData {
    val globalRows = rows.filter { it.libraryName == null && it.title != title } +
        listOfNotNull(items.takeIf { it.isNotEmpty() }?.let { MediaRow(title, items = it, wide = wide) })
    return copy(rows = globalRows.orderedHomeRows() + rows.filter { it.libraryName != null })
}

private fun HomeData.withLibraryData(
    libraries: List<com.zenstream.zenstreammobile.model.Library>,
    libraryData: com.zenstream.zenstreammobile.model.LibraryData,
): HomeData {
    val byLibrary = rows.filter { it.libraryName != null }
        .groupBy { it.libraryName }
        .toMutableMap()
    byLibrary[libraryData.library.name] = libraryData.rows
    val orderedLibraryRows = libraries.flatMap { byLibrary[it.name].orEmpty() }
    return copy(
        rows = rows.filter { it.libraryName == null }.orderedHomeRows() + orderedLibraryRows,
    )
}

private fun List<MediaRow>.orderedHomeRows(): List<MediaRow> =
    sortedBy { row ->
        when (row.title) {
            RowTitle.ContinueWatching -> 0
            RowTitle.NextUp -> 1
            else -> 2
        }
    }

data class LibraryUiState(
    val loading: Boolean = true,
    val libraries: List<Library> = emptyList(),
    val selected: Library? = null,
    val data: LibraryData? = null,
    val error: Boolean = false,
)

class LibraryViewModel(
    private val repository: JellyfinRepository,
    private val session: AuthSession
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadLibraries()
    }

    fun loadLibraries() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = false)
            runCatching { repository.libraries(session) }
                .onSuccess { libraries ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        libraries = libraries,
                        selected = libraries.firstOrNull()
                    )
                    libraries.firstOrNull()?.let(::select)
                }
                .onFailure {
                    if ((it as? JellyfinException)?.statusCode == 401) repository.clearSession()
                    _uiState.value = _uiState.value.copy(loading = false, error = true)
                }
        }
    }

    fun select(library: Library) {
        _uiState.value = _uiState.value.copy(selected = library, data = null, loading = true)
        viewModelScope.launch {
            runCatching { repository.library(session, library) }
                .onSuccess { _uiState.value = _uiState.value.copy(loading = false, data = it) }
                .onFailure {
                    if ((it as? JellyfinException)?.statusCode == 401) repository.clearSession()
                    _uiState.value = _uiState.value.copy(loading = false, error = true)
                }
        }
    }

    class Factory(private val repository: JellyfinRepository, private val session: AuthSession) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(repository, session) as T
    }
}

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<MediaItem> = emptyList(),
    val error: Boolean = false
)

class SearchViewModel(
    private val repository: JellyfinRepository,
    private val session: AuthSession
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState = _uiState.asStateFlow()
    private var searchJob: Job? = null

    fun updateQuery(value: String) {
        _uiState.value = _uiState.value.copy(query = value, error = false)
        searchJob?.cancel()
        if (value.trim().length < 2) {
            _uiState.value = _uiState.value.copy(loading = false, results = emptyList())
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            _uiState.value = _uiState.value.copy(loading = true)
            runCatching { repository.search(session, value) }
                .onSuccess { _uiState.value = _uiState.value.copy(loading = false, results = it) }
                .onFailure {
                    if ((it as? JellyfinException)?.statusCode == 401) repository.clearSession()
                    _uiState.value = _uiState.value.copy(loading = false, error = true)
                }
        }
    }

    class Factory(private val repository: JellyfinRepository, private val session: AuthSession) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(repository, session) as T
    }
}

data class DetailUiState(
    val loading: Boolean = true,
    val data: DetailData? = null,
    val error: Boolean = false,
    val actionBusy: Boolean = false,
    val actionError: Boolean = false,
)

class DetailViewModel(
    private val repository: JellyfinRepository,
    private val session: AuthSession,
    private val itemId: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = false)
            runCatching {
                repository.detail(
                    session,
                    itemId,
                    _uiState.value.data?.selectedSeasonId
                )
            }
                .onSuccess { _uiState.value = DetailUiState(loading = false, data = it) }
                .onFailure {
                    if ((it as? JellyfinException)?.statusCode == 401) repository.clearSession()
                    _uiState.value = _uiState.value.copy(loading = false, error = true)
                }
        }
    }

    fun selectSeason(seasonId: String) {
        val currentData = _uiState.value.data ?: return
        if (currentData.selectedSeasonId == seasonId) return
        _uiState.value = _uiState.value.copy(
            loading = true,
            error = false,
            data = currentData.copy(
                selectedSeasonId = seasonId,
                episodes = emptyList(),
            ),
        )
        viewModelScope.launch {
            runCatching { repository.detail(session, itemId, seasonId) }
                .onSuccess { _uiState.value = DetailUiState(loading = false, data = it) }
                .onFailure {
                    if ((it as? JellyfinException)?.statusCode == 401) repository.clearSession()
                    _uiState.value = DetailUiState(
                        loading = false,
                        data = currentData,
                        error = true,
                    )
                }
        }
    }

    fun togglePlayed() = toggleItemState(playedAction = true) { item, value ->
        repository.setPlayed(session, item.id, value)
    }

    fun toggleFavorite() = toggleItemState(playedAction = false) { item, value ->
        repository.setFavorite(session, item.id, value)
    }

    fun toggleSeasonPlayed(seasonId: String) = toggleSeasonState(seasonId, playedAction = true) { item, value ->
        repository.setPlayed(session, item.id, value)
    }

    fun toggleSeasonFavorite(seasonId: String) = toggleSeasonState(seasonId, playedAction = false) { item, value ->
        repository.setFavorite(session, item.id, value)
    }

    private fun toggleItemState(
        playedAction: Boolean,
        action: suspend (com.zenstream.zenstreammobile.model.MediaItem, Boolean) -> Unit,
    ) {
        val current = _uiState.value.data ?: return
        val previous = current.item
        viewModelScope.launch {
            val targetValue = if (playedAction) !previous.played else !previous.favorite
            val optimistic = if (playedAction) {
                previous.copy(played = targetValue)
            } else {
                previous.copy(favorite = targetValue)
            }
            _uiState.value = _uiState.value.copy(
                data = current.copy(item = optimistic),
                actionBusy = true,
                actionError = false,
            )
            runCatching { action(previous, targetValue) }
                .onFailure {
                    if ((it as? JellyfinException)?.statusCode == 401) repository.clearSession()
                    _uiState.value = _uiState.value.copy(
                        data = current,
                        actionBusy = false,
                        actionError = true,
                    )
                }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(actionBusy = false)
                }
        }
    }

    private fun toggleSeasonState(
        seasonId: String,
        playedAction: Boolean,
        action: suspend (com.zenstream.zenstreammobile.model.MediaItem, Boolean) -> Unit,
    ) {
        val current = _uiState.value.data ?: return
        val previous = current.seasons.firstOrNull { it.id == seasonId } ?: return
        viewModelScope.launch {
            val targetValue = if (playedAction) !previous.played else !previous.favorite
            val optimisticSeason = if (playedAction) {
                previous.copy(played = targetValue)
            } else {
                previous.copy(favorite = targetValue)
            }
            _uiState.value = _uiState.value.copy(
                data = current.copy(
                    seasons = current.seasons.map { season ->
                        if (season.id == seasonId) optimisticSeason else season
                    },
                ),
                actionBusy = true,
                actionError = false,
            )
            runCatching { action(previous, targetValue) }
                .onFailure {
                    if ((it as? JellyfinException)?.statusCode == 401) repository.clearSession()
                    _uiState.value = _uiState.value.copy(
                        data = current,
                        actionBusy = false,
                        actionError = true,
                    )
                }
                .onSuccess {
                    _uiState.value = _uiState.value.copy(actionBusy = false)
                }
        }
    }

    class Factory(
        private val repository: JellyfinRepository,
        private val session: AuthSession,
        private val itemId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DetailViewModel(repository, session, itemId) as T
    }
}

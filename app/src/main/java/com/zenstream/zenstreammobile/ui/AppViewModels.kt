package com.zenstream.zenstreammobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zenstream.zenstreammobile.data.HomeDataSource
import com.zenstream.zenstreammobile.data.CatalogException
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.LibraryDataSource
import com.zenstream.zenstreammobile.data.SearchDataSource
import com.zenstream.zenstreammobile.data.SyncplaySession
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.DetailData
import com.zenstream.zenstreammobile.model.HomeData
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.LibrarySortBy
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaRow
import com.zenstream.zenstreammobile.model.RowTitle
import com.zenstream.zenstreammobile.model.orderedHomeRows
import com.zenstream.zenstreammobile.model.MediaSource
import com.zenstream.zenstreammobile.model.PlaybackTrackSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

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

class AppViewModel(private val repository: CatalogRepository) : ViewModel() {
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
    fun logout() = viewModelScope.launch { SyncplaySession.clear(); repository.clearSession() }
    fun changeServer() = viewModelScope.launch { SyncplaySession.clear(); repository.clearAll() }

    class Factory(private val repository: CatalogRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(repository) as T
    }
}

data class LoginUiState(
    val username: String = "",
    val busy: Boolean = false,
    val error: String? = null
)

class LoginViewModel(private val repository: CatalogRepository) : ViewModel() {
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

    class Factory(private val repository: CatalogRepository) : ViewModelProvider.Factory {
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
        const val INITIAL_SECTION_COUNT = 5
    }

    init {
        load()
        viewModelScope.launch {
            repository.catalogRefreshRevision.drop(1).collectLatest {
                load(force = true)
            }
        }
        viewModelScope.launch {
            repository.catalogChanges.collectLatest { load(force = true) }
        }
    }

    fun load(force: Boolean = false) {
        if (loadingJob?.isActive == true) {
            if (!force) return
            loadingJob?.cancel()
        }
        if (!force && !_uiState.value.loading && _uiState.value.data != null) return
        _uiState.value = if (force) {
            HomeUiState(loading = true, data = HomeData(), pendingSections = INITIAL_SECTION_COUNT)
        } else {
            HomeUiState(loading = true, pendingSections = INITIAL_SECTION_COUNT)
        }
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
                launch {
                    loadSection(
                        request = { repository.homeDerived(session) },
                        apply = { data, derived -> data.withDerivedRows(derived.rows()) },
                    )
                }
                launch { loadLibraries() }
            }
        }
    }

    fun refresh() = load(force = true)

    private suspend fun CoroutineScope.loadLibraries() {
        try {
            val libraries = repository.homeLibraries(session)
            if (libraries.isNotEmpty()) addPendingSections(libraries.size)
            completeSection(success = true)
            if (libraries.isEmpty()) return
            libraries.map { library ->
                launch {
                    loadSection(
                        request = { repository.homeLibraryData(session, library) },
                        apply = { data, libraryData -> data.withLibraryData(libraries, libraryData) },
                    )
                }
            }.joinAll()
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
            _uiState.update { state -> state.copy(data = apply(state.data ?: HomeData(), result)) }
            completeSection(success = true)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            handleFailure(error)
            completeSection(success = false)
        }
    }

    private suspend fun handleFailure(error: Throwable) {
        if ((error as? CatalogException)?.statusCode == 401) repository.clearSession()
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

    class Factory(private val repository: CatalogRepository, private val session: AuthSession) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HomeViewModel(repository, session) as T
    }
}

private fun HomeData.withRow(title: RowTitle, items: List<MediaItem>, wide: Boolean): HomeData {
    val globalRows = rows.filter { it.libraryName == null && it.title != title } +
            listOfNotNull(items.takeIf { it.isNotEmpty() }?.let { MediaRow(title, items = it, wide = wide) })
    return copy(rows = orderedHomeRows(globalRows + rows.filter { it.libraryName != null }))
}

private fun HomeData.withDerivedRows(derivedRows: List<MediaRow>): HomeData {
    val derivedTitles = setOf(RowTitle.MyList, RowTitle.RecentlyPlayed, RowTitle.Genre)
    val globalRows = rows.filter { it.libraryName == null && it.title !in derivedTitles } + derivedRows
    return copy(rows = orderedHomeRows(globalRows + rows.filter { it.libraryName != null }))
}

private fun HomeData.withLibraryData(
    libraries: List<Library>,
    libraryData: com.zenstream.zenstreammobile.model.LibraryData,
): HomeData {
    val byLibrary = rows.filter { it.libraryName != null }.groupBy { it.libraryName }.toMutableMap()
    byLibrary[libraryData.library.name] = libraryData.rows
    return copy(rows = orderedHomeRows(
        rows.filter { it.libraryName == null } + libraries.flatMap { byLibrary[it.name].orEmpty() },
    ))
}

data class LibraryUiState(
    val loading: Boolean = true,
    val libraries: List<Library> = emptyList(),
    val selected: Library? = null,
    val sort: LibrarySort = LibrarySort(),
    val items: List<MediaItem> = emptyList(),
    val totalRecordCount: Int = 0,
    val loadingMore: Boolean = false,
    val error: Boolean = false,
    val loadMoreError: Boolean = false,
)

class LibraryViewModel(
    private val repository: LibraryDataSource,
    private val session: AuthSession
) : ViewModel() {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState = _uiState.asStateFlow()
    private var requestGeneration = 0L
    private var requestJob: Job? = null

    init {
        loadLibraries()
        viewModelScope.launch {
            repository.catalogRefreshRevision.drop(1).collectLatest {
                refresh()
            }
        }
        viewModelScope.launch {
            repository.catalogChanges.collectLatest { change ->
                if (change.libraryId == null || change.libraryId == _uiState.value.selected?.id) refresh()
            }
        }
    }

    fun loadLibraries(preferredLibraryId: String? = null) {
        requestJob?.cancel()
        val generation = ++requestGeneration
        _uiState.value = _uiState.value.copy(
            loading = true,
            error = false,
            loadMoreError = false,
        )
        requestJob = viewModelScope.launch {
            val result = runCatching { repository.libraries(session) }
            if (generation != requestGeneration) return@launch
            result
                .onSuccess { libraries ->
                    val currentId = _uiState.value.selected?.id
                    val selected = libraries.firstOrNull { it.id == preferredLibraryId }
                        ?: libraries.firstOrNull { it.id == currentId }
                        ?: libraries.firstOrNull()
                    _uiState.value = _uiState.value.copy(
                        loading = selected != null,
                        libraries = libraries,
                        selected = selected,
                        items = emptyList(),
                        totalRecordCount = 0,
                    )
                    selected?.let { library ->
                        viewModelScope.launch {
                            val storedSort = normalizeLibrarySort(
                                library,
                                repository.cachedLibrarySort(session.userId, library.id)
                                    ?: LibrarySort(),
                            )
                            if (generation != requestGeneration) return@launch
                            _uiState.update { it.copy(sort = storedSort) }
                            loadFirstPage(library, generation, storedSort)
                        }
                    }
                }
                .onFailure {
                    if ((it as? CatalogException)?.statusCode == 401) repository.clearSession()
                    _uiState.value = _uiState.value.copy(loading = false, error = true)
                }
        }
    }

    fun refresh() = loadLibraries(_uiState.value.selected?.id)

    fun select(library: Library) {
        if (_uiState.value.selected?.id == library.id && !_uiState.value.loading) return
        requestJob?.cancel()
        val generation = ++requestGeneration
        _uiState.value = _uiState.value.copy(
            selected = library,
            loading = true,
            error = false,
            loadMoreError = false,
            items = emptyList(),
            totalRecordCount = 0,
            sort = LibrarySort(),
        )
        requestJob = viewModelScope.launch {
            val storedSort = normalizeLibrarySort(
                library,
                repository.cachedLibrarySort(session.userId, library.id) ?: LibrarySort(),
            )
            if (generation != requestGeneration) return@launch
            _uiState.update { it.copy(sort = storedSort) }
            loadFirstPage(library, generation, storedSort)
        }
    }

    fun setSort(sort: LibrarySort) {
        val library = _uiState.value.selected ?: return
        if (_uiState.value.sort == sort) return
        requestJob?.cancel()
        val generation = ++requestGeneration
        _uiState.value = _uiState.value.copy(
            sort = sort,
            loading = true,
            error = false,
            loadMoreError = false,
            items = emptyList(),
            totalRecordCount = 0,
        )
        requestJob = viewModelScope.launch {
            repository.saveLibrarySort(session.userId, library.id, sort)
            if (generation != requestGeneration) return@launch
            loadFirstPage(library, generation, sort)
        }
    }

    fun loadMore() {
        val state = _uiState.value
        val library = state.selected ?: return
        if (state.loading || state.loadingMore || state.items.size >= state.totalRecordCount) return
        val generation = requestGeneration
        val startIndex = state.items.size
        _uiState.value = state.copy(loadingMore = true, loadMoreError = false)
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            runCatching {
                repository.libraryPage(
                    session,
                    library,
                    startIndex = startIndex,
                    limit = LIBRARY_PAGE_SIZE,
                    sort = _uiState.value.sort,
                )
            }
                .onSuccess { page ->
                    if (generation != requestGeneration) return@onSuccess
                    _uiState.update { current ->
                        current.copy(
                            items = uniqueItems(current.items + page.items),
                            totalRecordCount = page.totalRecordCount,
                            loadingMore = false,
                            loadMoreError = false,
                        )
                    }
                }
                .onFailure {
                    if (generation != requestGeneration) return@onFailure
                    if ((it as? CatalogException)?.statusCode == 401) repository.clearSession()
                    _uiState.update { current ->
                        current.copy(loadingMore = false, loadMoreError = true)
                    }
                }
        }
    }

    private suspend fun loadFirstPage(
        library: Library,
        generation: Long,
        sort: LibrarySort = _uiState.value.sort,
    ) {
        runCatching {
            repository.libraryPage(
                session,
                library,
                startIndex = 0,
                limit = LIBRARY_PAGE_SIZE,
                sort = sort,
            )
        }
            .onSuccess { page ->
                if (generation != requestGeneration) return@onSuccess
                _uiState.update {
                    it.copy(
                        loading = false,
                        items = uniqueItems(page.items),
                        totalRecordCount = page.totalRecordCount,
                        error = false,
                        loadMoreError = false,
                    )
                }
            }
            .onFailure {
                if (generation != requestGeneration) return@onFailure
                if ((it as? CatalogException)?.statusCode == 401) repository.clearSession()
                _uiState.update { current -> current.copy(loading = false, error = true) }
            }
    }

    private fun uniqueItems(items: List<MediaItem>): List<MediaItem> {
        val seen = HashSet<String>()
        return items.filter { seen.add(it.id) }
    }

    class Factory(private val repository: LibraryDataSource, private val session: AuthSession) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LibraryViewModel(repository, session) as T
    }
}

private fun normalizeLibrarySort(library: Library, sort: LibrarySort): LibrarySort =
    if (!library.supportsLastAdded && sort.sortBy == LibrarySortBy.LastAdded) {
        sort.copy(sortBy = LibrarySortBy.Added)
    } else {
        sort
    }

data class SearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<MediaItem> = emptyList(),
    val error: Boolean = false
)

class SearchViewModel(
    private val repository: SearchDataSource,
    private val session: AuthSession
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState = _uiState.asStateFlow()
    private var searchJob: Job? = null
    private var requestGeneration = 0L

    init {
        viewModelScope.launch {
            repository.catalogRefreshRevision.drop(1).collectLatest {
                refresh()
            }
        }
        viewModelScope.launch {
            repository.catalogChanges.collectLatest { refresh() }
        }
    }

    fun updateQuery(value: String) {
        val generation = ++requestGeneration
        _uiState.value = _uiState.value.copy(
            query = value,
            loading = value.trim().length >= 2,
            error = false,
        )
        searchJob?.cancel()
        if (value.trim().length < 2) {
            _uiState.value = _uiState.value.copy(loading = false, results = emptyList())
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            search(generation, value)
        }
    }

    fun retry() {
        val query = _uiState.value.query
        if (query.trim().length < 2) return
        searchJob?.cancel()
        val generation = ++requestGeneration
        searchJob = viewModelScope.launch { search(generation, query) }
    }

    private suspend fun search(generation: Long, query: String) {
        if (generation != requestGeneration) return
        _uiState.value = _uiState.value.copy(loading = true)
        runCatching { repository.search(session, query) }
            .onSuccess {
                if (generation != requestGeneration) return@onSuccess
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    results = rankSearchResults(it, query),
                    error = false,
                )
            }
            .onFailure {
                if (generation != requestGeneration) return@onFailure
                if ((it as? CatalogException)?.statusCode == 401) repository.clearSession()
                _uiState.value = _uiState.value.copy(loading = false, error = true)
            }
    }

    fun refresh() {
        retry()
    }

    class Factory(private val repository: SearchDataSource, private val session: AuthSession) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(repository, session) as T
    }
}

internal fun rankSearchResults(items: List<MediaItem>, query: String): List<MediaItem> {
    val terms = query.trim().lowercase().split(Regex("\\s+"))
        .filter(String::isNotBlank)
    val normalizedQuery = terms.joinToString(" ")
    return items.mapIndexed { index, item ->
        val title = item.name.trim().lowercase()
        val words = title.split(Regex("\\s+"))
        val score = when {
            title == normalizedQuery -> 1000
            title.startsWith(normalizedQuery) -> 700
            terms.all { term -> words.any { it.startsWith(term) } } -> 500
            terms.all(title::contains) -> 300
            else -> terms.sumOf { term -> if (title.contains(term)) 1 else 0 } * 50
        }
        Triple(index, score, item)
    }
        .sortedWith(compareByDescending<Triple<Int, Int, MediaItem>> { it.second }.thenBy { it.first })
        .map { it.third }
}

private const val LIBRARY_PAGE_SIZE = 40

data class DetailUiState(
    val loading: Boolean = true,
    val data: DetailData? = null,
    val error: Boolean = false,
    val seasonLoading: Boolean = false,
    val actionBusy: Boolean = false,
    val actionError: Boolean = false,
    val trackSource: MediaSource? = null,
    val trackSelection: PlaybackTrackSelection? = null,
)

internal fun defaultTrackSelection(source: MediaSource): PlaybackTrackSelection {
    val audio = source.mediaStreams.filter { it.type.equals("audio", true) }
    val subtitles = source.mediaStreams.filter { it.type.equals("subtitle", true) }
    return PlaybackTrackSelection(
        audioStreamId = audio.firstOrNull { it.isDefault }?.index ?: audio.firstOrNull()?.index,
        subtitleStreamIndex = subtitles.firstOrNull { it.isDefault }?.index
            ?: subtitles.firstOrNull()?.index,
        hasSubtitleSelection = subtitles.isNotEmpty(),
    )
}

class DetailViewModel(
    private val repository: CatalogRepository,
    private val session: AuthSession,
    private val itemId: String,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState = _uiState.asStateFlow()
    private var loadGeneration = 0L
    private var loadJob: Job? = null
    private var trackLoadJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            repository.catalogRefreshRevision.drop(1).collectLatest {
                load()
            }
        }
        viewModelScope.launch {
            repository.catalogChanges.collectLatest { change ->
                if (change.libraryId == null || change.libraryId == _uiState.value.data?.item?.libraryId) load()
            }
        }
    }

    fun load() {
        loadJob?.cancel()
        val generation = ++loadGeneration
        loadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                loading = true,
                seasonLoading = false,
                error = false,
            )
            runCatching {
                repository.detail(
                    session,
                    itemId,
                    _uiState.value.data?.selectedSeasonId
                )
            }
                .onSuccess {
                    if (generation != loadGeneration) return@onSuccess
                    _uiState.value = DetailUiState(loading = false, data = it)
                    loadTrackSource(generation, it.item)
                }
                .onFailure {
                    if (generation != loadGeneration) return@onFailure
                    if ((it as? CatalogException)?.statusCode == 401) repository.clearSession()
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        seasonLoading = false,
                        error = true,
                    )
                }
        }
    }

    fun selectSeason(seasonId: String) {
        val currentData = _uiState.value.data ?: return
        if (currentData.selectedSeasonId == seasonId) return
        loadJob?.cancel()
        val generation = ++loadGeneration
        _uiState.value = _uiState.value.copy(
            loading = true,
            seasonLoading = true,
            error = false,
            data = currentData.copy(
                selectedSeasonId = seasonId,
                episodes = emptyList(),
            ),
        )
        loadJob = viewModelScope.launch {
            runCatching { repository.detail(session, itemId, seasonId) }
                .onSuccess {
                    if (generation != loadGeneration) return@onSuccess
                    _uiState.value = DetailUiState(loading = false, data = it)
                    loadTrackSource(generation, it.item)
                }
                .onFailure {
                    if (generation != loadGeneration) return@onFailure
                    if ((it as? CatalogException)?.statusCode == 401) repository.clearSession()
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

    fun selectAudioTrack(streamIndex: Int) {
        val current = _uiState.value
        val source = current.trackSource ?: return
        if (source.mediaStreams.none { it.type.equals("audio", true) && it.index == streamIndex }) return
        _uiState.value = current.copy(
            trackSelection = (current.trackSelection ?: defaultTrackSelection(source)).copy(
                audioStreamId = streamIndex,
            ),
        )
    }

    fun selectSubtitleTrack(streamIndex: Int?) {
        val current = _uiState.value
        val source = current.trackSource ?: return
        if (streamIndex != null && source.mediaStreams.none {
                it.type.equals("subtitle", true) && it.index == streamIndex
            }) return
        _uiState.value = current.copy(
            trackSelection = (current.trackSelection ?: defaultTrackSelection(source)).copy(
                subtitleStreamIndex = streamIndex,
                hasSubtitleSelection = true,
            ),
        )
    }

    fun playbackTrackSelection(): PlaybackTrackSelection? = _uiState.value.trackSelection

    private fun loadTrackSource(generation: Long, item: MediaItem) {
        trackLoadJob?.cancel()
        if (item.type !in setOf("Movie", "Episode")) return
        trackLoadJob = viewModelScope.launch {
            val source = runCatching { repository.playbackSource(session, item.id) }.getOrNull()
                ?: return@launch
            if (generation != loadGeneration || _uiState.value.data?.item?.id != item.id) return@launch
            _uiState.value = _uiState.value.copy(
                trackSource = source,
                trackSelection = defaultTrackSelection(source),
            )
        }
    }

    fun toggleSeasonPlayed(seasonId: String) =
        toggleSeasonState(seasonId, playedAction = true) { item, value ->
            repository.setPlayed(session, item.id, value)
        }

    fun toggleSeasonFavorite(seasonId: String) =
        toggleSeasonState(seasonId, playedAction = false) { item, value ->
            repository.setFavorite(session, item.id, value)
        }

    private fun toggleItemState(
        playedAction: Boolean,
        action: suspend (MediaItem, Boolean) -> Unit,
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
                    if ((it as? CatalogException)?.statusCode == 401) repository.clearSession()
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
        action: suspend (MediaItem, Boolean) -> Unit,
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
                    if ((it as? CatalogException)?.statusCode == 401) repository.clearSession()
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
        private val repository: CatalogRepository,
        private val session: AuthSession,
        private val itemId: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            DetailViewModel(repository, session, itemId) as T
    }
}

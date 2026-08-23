package com.zenstream.zenstreammobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zenstream.zenstreammobile.data.AppUpdate
import com.zenstream.zenstreammobile.data.CatalogException
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.FavoritesDataSource
import com.zenstream.zenstreammobile.data.GitHubUpdateChecker
import com.zenstream.zenstreammobile.data.HomeDataSource
import com.zenstream.zenstreammobile.data.LibraryDataSource
import com.zenstream.zenstreammobile.data.PlaybackPreference
import com.zenstream.zenstreammobile.data.SearchDataSource
import com.zenstream.zenstreammobile.data.SyncplaySession
import com.zenstream.zenstreammobile.data.UpdateSource
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.BazarrSearchResult
import com.zenstream.zenstreammobile.model.BazarrStatus
import com.zenstream.zenstreammobile.model.DetailData
import com.zenstream.zenstreammobile.model.FavoriteSort
import com.zenstream.zenstreammobile.model.HomeData
import com.zenstream.zenstreammobile.model.Library
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.LibrarySortBy
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.MediaRow
import com.zenstream.zenstreammobile.model.MediaSource
import com.zenstream.zenstreammobile.model.NotificationItem
import com.zenstream.zenstreammobile.model.PlaybackTrackSelection
import com.zenstream.zenstreammobile.model.RowTitle
import com.zenstream.zenstreammobile.model.orderedHomeRows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
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
    val availableUpdate: AppUpdate? = null,
) {
    val showSetup
        get() = !loading && (orchestratorUrl.isNullOrBlank() || serverUrl.isNullOrBlank())

    val showLogin
        get() = !loading && !showSetup && session == null

    val showMain
        get() = !loading && !showSetup && session != null
}

class AppViewModel(
    private val repository: CatalogRepository,
    private val updateSource: UpdateSource = GitHubUpdateChecker(),
) : ViewModel() {
    private var accountRefreshToken: String? = null
    private val _availableUpdate = MutableStateFlow<AppUpdate?>(null)

    val uiState: StateFlow<AppUiState> =
        combine(
                repository.orchestratorUrl,
                repository.serverUrl,
                repository.session,
                repository.locale,
                _availableUpdate,
            ) { orchestrator, server, session, locale, availableUpdate ->
                AppUiState(
                    loading = false,
                    orchestratorUrl = orchestrator,
                    serverUrl = server,
                    session = session,
                    locale = locale,
                    availableUpdate = availableUpdate,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

    init {
        viewModelScope.launch {
            repository.session.collect { session ->
                if (session == null) {
                    accountRefreshToken = null
                    return@collect
                }
                if (accountRefreshToken != session.token) {
                    // Account data is refreshed once per bearer token so avatar
                    // changes made on another client appear without creating a
                    // startup request loop when the session is persisted again.
                    accountRefreshToken = session.token
                    runCatching { repository.refreshCurrentAccount() }
                }
                runCatching { repository.syncInterfaceLocale(session) }
                runCatching { repository.loadWatchHistoryPreference() }
            }
        }
        viewModelScope.launch {
            if (!repository.checkForUpdatesOnStartup.first()) return@launch
            try {
                _availableUpdate.value = updateSource.checkForUpdate()
            } catch (error: CancellationException) {
                throw error
            }
        }
    }

    fun dismissAvailableUpdate() {
        _availableUpdate.value = null
    }

    suspend fun configureServer(value: String) = repository.configureOrchestrator(value)

    fun logout() = viewModelScope.launch {
        val active = repository.session.first()
        if (active != null) runCatching { repository.revokeSession(active) }
        SyncplaySession.clear()
        repository.clearSession()
    }

    fun passwordChanged() = viewModelScope.launch {
        SyncplaySession.clear()
        repository.clearSession()
    }

    fun changeServer() = viewModelScope.launch {
        val active = repository.session.first()
        if (active != null) runCatching { repository.revokeSession(active) }
        SyncplaySession.clear()
        repository.clearAll()
    }

    class Factory(private val repository: CatalogRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(repository) as T
    }
}

data class NotificationsUiState(
    val loading: Boolean = true,
    val items: List<NotificationItem> = emptyList(),
    val unreadCount: Int = 0,
    val nextCursor: String? = null,
    val error: Boolean = false,
    val loadingMore: Boolean = false,
)

class NotificationsViewModel(
    private val repository: CatalogRepository,
    private val session: AuthSession,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NotificationsUiState())
    val uiState = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(loading = true, error = false)
        loadJob = viewModelScope.launch {
            runCatching { repository.notifications(session) }
                .onSuccess { page ->
                    _uiState.value =
                        NotificationsUiState(
                            loading = false,
                            items = page.items,
                            unreadCount = page.unreadCount,
                            nextCursor = page.nextCursor,
                        )
                }
                .onFailure {
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                    _uiState.value = _uiState.value.copy(loading = false, error = true)
                }
        }
    }

    fun loadMore() {
        val cursor = _uiState.value.nextCursor ?: return
        if (_uiState.value.loading || _uiState.value.loadingMore) return
        _uiState.value = _uiState.value.copy(loadingMore = true)
        loadJob = viewModelScope.launch {
            runCatching { repository.notifications(session, cursor = cursor) }
                .onSuccess { page ->
                    _uiState.update { state ->
                        state.copy(
                            loadingMore = false,
                            items = (state.items + page.items).distinctBy { it.id },
                            unreadCount = page.unreadCount,
                            nextCursor = page.nextCursor,
                        )
                    }
                }
                .onFailure {
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                    _uiState.update { state -> state.copy(loadingMore = false, error = true) }
                }
        }
    }

    fun setRead(item: NotificationItem, read: Boolean) {
        val current = _uiState.value
        if ((item.readAt != null) == read) return
        val marker = if (read) "local" else null
        _uiState.value =
            current.copy(
                items =
                    current.items.map { if (it.id == item.id) it.copy(readAt = marker) else it },
                unreadCount = (current.unreadCount + if (read) -1 else 1).coerceAtLeast(0),
            )
        viewModelScope.launch {
            runCatching { repository.setNotificationRead(session, item.id, read) }
                .onFailure {
                    _uiState.value = current
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                }
        }
    }

    fun markAllRead() {
        val current = _uiState.value
        if (current.unreadCount == 0) return
        _uiState.value =
            current.copy(
                items =
                    current.items.map { if (it.readAt == null) it.copy(readAt = "local") else it },
                unreadCount = 0,
            )
        viewModelScope.launch {
            runCatching { repository.markAllNotificationsRead(session) }
                .onFailure {
                    _uiState.value = current
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                }
        }
    }

    fun remove(item: NotificationItem) {
        val current = _uiState.value
        if (current.items.none { it.id == item.id }) return
        _uiState.value =
            current.copy(
                items = current.items.filterNot { it.id == item.id },
                unreadCount =
                    (current.unreadCount - if (item.readAt == null) 1 else 0).coerceAtLeast(0),
            )
        viewModelScope.launch {
            runCatching { repository.deleteNotification(session, item.id) }
                .onFailure {
                    _uiState.value = current
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                }
        }
    }

    class Factory(
        private val repository: CatalogRepository,
        private val session: AuthSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            NotificationsViewModel(repository, session) as T
    }
}

data class LoginUiState(
    val username: String = "",
    val busy: Boolean = false,
    val error: String? = null,
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
    }

    fun load(force: Boolean = false) {
        if (loadingJob?.isActive == true) {
            if (!force) return
            loadingJob?.cancel()
        }
        if (!force && !_uiState.value.loading && _uiState.value.data != null) return
        _uiState.value =
            if (force) {
                HomeUiState(
                    loading = true,
                    data = HomeData(),
                    pendingSections = INITIAL_SECTION_COUNT,
                )
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
                        apply = { data, items ->
                            data.withRow(RowTitle.ContinueWatching, items, wide = true)
                        },
                    )
                }
                launch {
                    loadSection(
                        request = { repository.homeNextUp(session) },
                        apply = { data, items ->
                            data.withRow(RowTitle.NextUp, items, wide = true)
                        },
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
            libraries
                .map { library ->
                    launch {
                        loadSection(
                            request = { repository.homeLibraryData(session, library) },
                            apply = { data, libraryData ->
                                data.withLibraryData(libraries, libraryData)
                            },
                        )
                    }
                }
                .joinAll()
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
        if ((error as? CatalogException)?.statusCode == 401) {
            repository.clearSessionIfCurrent(session)
        }
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
    val globalRows =
        rows.filter { it.libraryName == null && it.title != title } +
            listOfNotNull(
                items.takeIf { it.isNotEmpty() }?.let { MediaRow(title, items = it, wide = wide) }
            )
    return copy(rows = orderedHomeRows(globalRows + rows.filter { it.libraryName != null }))
}

private fun HomeData.withDerivedRows(derivedRows: List<MediaRow>): HomeData {
    val derivedTitles = setOf(RowTitle.MyList, RowTitle.Genre)
    val globalRows =
        rows.filter { it.libraryName == null && it.title !in derivedTitles } + derivedRows
    return copy(rows = orderedHomeRows(globalRows + rows.filter { it.libraryName != null }))
}

private fun HomeData.withLibraryData(
    libraries: List<Library>,
    libraryData: com.zenstream.zenstreammobile.model.LibraryData,
): HomeData {
    val byLibrary = rows.filter { it.libraryName != null }.groupBy { it.libraryName }.toMutableMap()
    byLibrary[libraryData.library.name] = libraryData.rows
    return copy(
        rows =
            orderedHomeRows(
                rows.filter { it.libraryName == null } +
                    libraries.flatMap { byLibrary[it.name].orEmpty() }
            )
    )
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
    private val session: AuthSession,
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
    }

    fun loadLibraries(preferredLibraryId: String? = null) {
        requestJob?.cancel()
        val generation = ++requestGeneration
        _uiState.value =
            _uiState.value.copy(
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
                    val selected =
                        libraries.firstOrNull { it.id == preferredLibraryId }
                            ?: libraries.firstOrNull { it.id == currentId }
                            ?: libraries.firstOrNull()
                    _uiState.value =
                        _uiState.value.copy(
                            loading = selected != null,
                            libraries = libraries,
                            selected = selected,
                            items = emptyList(),
                            totalRecordCount = 0,
                        )
                    selected?.let { library ->
                        viewModelScope.launch {
                            val storedSort =
                                normalizeLibrarySort(
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
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                    _uiState.value = _uiState.value.copy(loading = false, error = true)
                }
        }
    }

    fun refresh() = loadLibraries(_uiState.value.selected?.id)

    fun select(library: Library) {
        if (_uiState.value.selected?.id == library.id && !_uiState.value.loading) return
        requestJob?.cancel()
        val generation = ++requestGeneration
        _uiState.value =
            _uiState.value.copy(
                selected = library,
                loading = true,
                error = false,
                loadMoreError = false,
                items = emptyList(),
                totalRecordCount = 0,
                sort = LibrarySort(),
            )
        requestJob = viewModelScope.launch {
            val storedSort =
                normalizeLibrarySort(
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
        _uiState.value =
            _uiState.value.copy(
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
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
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
                if ((it as? CatalogException)?.statusCode == 401) {
                    repository.clearSessionIfCurrent(session)
                }
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
    val resultQuery: String = "",
    val loading: Boolean = false,
    val results: List<MediaItem> = emptyList(),
    val totalRecordCount: Int = 0,
    val nextPage: Int = 1,
    val loadingMore: Boolean = false,
    val error: Boolean = false,
    val loadMoreError: Boolean = false,
)

class SearchViewModel(
    private val repository: SearchDataSource,
    private val session: AuthSession,
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
    }

    fun updateQuery(value: String) {
        val generation = ++requestGeneration
        val normalized = value.trim()
        searchJob?.cancel()
        _uiState.value =
            _uiState.value.copy(
                query = value,
                loading = normalized.isNotEmpty(),
                error = false,
                loadingMore = false,
                loadMoreError = false,
                nextPage = 1,
            )
        if (normalized.isEmpty()) {
            _uiState.value =
                _uiState.value.copy(
                    loading = false,
                    resultQuery = "",
                    results = emptyList(),
                    totalRecordCount = 0,
                    nextPage = 1,
                )
            return
        }
        searchJob = viewModelScope.launch { search(generation, value, page = 1) }
    }

    fun retry() {
        val query = _uiState.value.query
        if (query.trim().isEmpty()) return
        searchJob?.cancel()
        val generation = ++requestGeneration
        _uiState.value =
            _uiState.value.copy(
                loading = true,
                error = false,
                loadingMore = false,
                loadMoreError = false,
                nextPage = 1,
            )
        searchJob = viewModelScope.launch { search(generation, query, page = 1) }
    }

    fun loadMore() {
        val state = _uiState.value
        val query = state.resultQuery.ifBlank { state.query }.trim()
        if (
            query.isEmpty() ||
                state.loading ||
                state.loadingMore ||
                state.results.size >= state.totalRecordCount
        )
            return
        val generation = requestGeneration
        val page = state.nextPage
        _uiState.value = state.copy(loadingMore = true, loadMoreError = false)
        searchJob = viewModelScope.launch {
            runCatching { repository.search(session, query, page) }
                .onSuccess { result ->
                    if (generation != requestGeneration) return@onSuccess
                    _uiState.update { current ->
                        current.copy(
                            results =
                                uniqueSearchItems(
                                    current.results + rankSearchResults(result.items, query)
                                ),
                            totalRecordCount = result.totalRecordCount,
                            nextPage = page + 1,
                            loadingMore = false,
                            loadMoreError = false,
                        )
                    }
                }
                .onFailure {
                    if (generation != requestGeneration) return@onFailure
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                    _uiState.update { current ->
                        current.copy(loadingMore = false, loadMoreError = true)
                    }
                }
        }
    }

    private suspend fun search(generation: Long, query: String, page: Int) {
        if (generation != requestGeneration) return
        _uiState.value = _uiState.value.copy(loading = page == 1)
        runCatching { repository.search(session, query, page) }
            .onSuccess { result ->
                if (generation != requestGeneration) return@onSuccess
                _uiState.value =
                    _uiState.value.copy(
                        loading = false,
                        resultQuery = query.trim(),
                        results = uniqueSearchItems(rankSearchResults(result.items, query)),
                        totalRecordCount = result.totalRecordCount,
                        nextPage = page + 1,
                        error = false,
                        loadMoreError = false,
                    )
            }
            .onFailure {
                if (generation != requestGeneration) return@onFailure
                if ((it as? CatalogException)?.statusCode == 401) {
                    repository.clearSessionIfCurrent(session)
                }
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

data class FavoritesUiState(
    val loading: Boolean = true,
    val sort: FavoriteSort = FavoriteSort(),
    val items: List<MediaItem> = emptyList(),
    val totalRecordCount: Int = 0,
    val loadingMore: Boolean = false,
    val error: Boolean = false,
    val loadMoreError: Boolean = false,
)

class FavoritesViewModel(
    private val repository: FavoritesDataSource,
    private val session: AuthSession,
) : ViewModel() {
    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState = _uiState.asStateFlow()
    private var requestGeneration = 0L
    private var requestJob: Job? = null

    init {
        viewModelScope.launch {
            val stored = runCatching { repository.cachedFavoriteSort(session.userId) }.getOrNull()
            _uiState.update { it.copy(sort = stored ?: FavoriteSort()) }
            load()
        }
        viewModelScope.launch {
            repository.catalogRefreshRevision.drop(1).collectLatest { refresh() }
        }
    }

    fun refresh() = load()

    fun setSort(sort: FavoriteSort) {
        if (_uiState.value.sort == sort) return
        _uiState.update { it.copy(sort = sort) }
        val generation = beginLoad()
        requestJob = viewModelScope.launch {
            runCatching { repository.saveFavoriteSort(session.userId, sort) }
            if (generation == requestGeneration) loadPages(generation, sort)
        }
    }

    private fun load() {
        val generation = beginLoad()
        val sort = _uiState.value.sort
        requestJob = viewModelScope.launch { loadPages(generation, sort) }
    }

    private fun beginLoad(): Long {
        requestJob?.cancel()
        val generation = ++requestGeneration
        _uiState.update {
            it.copy(
                loading = true,
                items = emptyList(),
                totalRecordCount = 0,
                error = false,
                loadMoreError = false,
                loadingMore = false,
            )
        }
        return generation
    }

    private suspend fun loadPages(generation: Long, sort: FavoriteSort) {
        var startIndex = 0
        var allItems = emptyList<MediaItem>()
        var total = 0
        try {
            while (generation == requestGeneration) {
                if (startIndex > 0) _uiState.update { it.copy(loadingMore = true) }
                val page = repository.favoritesPage(session, startIndex, FAVORITES_PAGE_SIZE, sort)
                total = page.totalRecordCount
                val merged = uniqueItems(allItems + page.items)
                val madeProgress = merged.size > allItems.size
                allItems = merged
                _uiState.update {
                    it.copy(
                        loading = false,
                        loadingMore = startIndex + page.items.size < total,
                        items = allItems,
                        totalRecordCount = total,
                        error = false,
                        loadMoreError = false,
                    )
                }
                if (allItems.size >= total || page.items.isEmpty() || !madeProgress) break
                startIndex += page.items.size
            }
            if (generation == requestGeneration) {
                _uiState.update { it.copy(loading = false, loadingMore = false) }
            }
        } catch (error: Throwable) {
            if (error is CancellationException || generation != requestGeneration) throw error
            if ((error as? CatalogException)?.statusCode == 401) {
                repository.clearSessionIfCurrent(session)
            }
            _uiState.update {
                it.copy(
                    loading = false,
                    loadingMore = false,
                    error = it.items.isEmpty(),
                    loadMoreError = it.items.isNotEmpty(),
                )
            }
        }
    }

    private fun uniqueItems(items: List<MediaItem>): List<MediaItem> {
        val seen = HashSet<String>()
        return items.filter { seen.add(it.id) }
    }

    class Factory(private val repository: FavoritesDataSource, private val session: AuthSession) :
        ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FavoritesViewModel(repository, session) as T
    }
}

private const val FAVORITES_PAGE_SIZE = 100

internal fun rankSearchResults(items: List<MediaItem>, query: String): List<MediaItem> {
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotBlank)
    val normalizedQuery = terms.joinToString(" ")
    return items
        .mapIndexed { index, item ->
            val title = item.name.trim().lowercase()
            val words = title.split(Regex("\\s+"))
            val score =
                when {
                    title == normalizedQuery -> 1000
                    title.startsWith(normalizedQuery) -> 700
                    terms.all { term -> words.any { it.startsWith(term) } } -> 500
                    terms.all(title::contains) -> 300
                    else -> terms.sumOf { term -> if (title.contains(term)) 1 else 0 } * 50
                }
            Triple(index, score, item)
        }
        .sortedWith(
            compareByDescending<Triple<Int, Int, MediaItem>> { it.second }.thenBy { it.first }
        )
        .map { it.third }
}

private fun uniqueSearchItems(items: List<MediaItem>): List<MediaItem> {
    val seen = HashSet<String>()
    return items.filter { seen.add(it.id) }
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
    val bazarrStatus: BazarrStatus? = null,
    val bazarrSearch: BazarrSearchResult? = null,
    val bazarrBusy: Boolean = false,
    val bazarrError: Boolean = false,
)

internal fun defaultTrackSelection(
    source: MediaSource,
    preference: PlaybackPreference? = null,
): PlaybackTrackSelection {
    val audio = source.mediaStreams.filter { it.type.equals("audio", true) }
    val subtitles = source.mediaStreams.filter { it.type.equals("subtitle", true) }
    return PlaybackTrackSelection(
        audioStreamId = preferredTrackIndex(audio, preference?.audioLanguage),
        subtitleStreamIndex = preferredSubtitleIndex(subtitles, preference?.subtitleLanguage),
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
    private var bazarrSearchGeneration = 0L
    private var bazarrSearchJob: Job? = null

    init {
        load()
        viewModelScope.launch {
            repository.catalogRefreshRevision.drop(1).collectLatest {
                load()
            }
        }
    }

    fun load() {
        loadJob?.cancel()
        val generation = ++loadGeneration
        val requestedSeasonId = _uiState.value.data?.selectedSeasonId
        loadJob = viewModelScope.launch {
            _uiState.value =
                _uiState.value.copy(
                    loading = true,
                    seasonLoading = false,
                    error = false,
                )
            runCatching {
                    repository.detail(
                        session,
                        itemId,
                        requestedSeasonId,
                    )
                }
                .onSuccess {
                    if (generation != loadGeneration) return@onSuccess
                    val data = keepSelectedSeason(it, requestedSeasonId)
                    _uiState.value = DetailUiState(loading = false, data = data)
                    loadTrackSource(generation, data.item)
                }
                .onFailure {
                    if (generation != loadGeneration) return@onFailure
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                    _uiState.value =
                        _uiState.value.copy(
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
        _uiState.value =
            _uiState.value.copy(
                loading = true,
                seasonLoading = true,
                error = false,
                data =
                    currentData.copy(
                        selectedSeasonId = seasonId,
                        episodes = emptyList(),
                    ),
            )
        loadJob = viewModelScope.launch {
            runCatching { repository.detail(session, itemId, seasonId) }
                .onSuccess {
                    if (generation != loadGeneration) return@onSuccess
                    val data = keepSelectedSeason(it, seasonId)
                    _uiState.value = DetailUiState(loading = false, data = data)
                    loadTrackSource(generation, data.item)
                }
                .onFailure {
                    if (generation != loadGeneration) return@onFailure
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                    _uiState.value =
                        DetailUiState(
                            loading = false,
                            data = currentData,
                            error = true,
                        )
                }
        }
    }

    fun togglePlayed() =
        toggleItemState(playedAction = true) { item, value ->
            repository.setPlayed(session, item.id, value)
        }

    fun toggleFavorite() =
        toggleItemState(playedAction = false) { item, value ->
            repository.setFavorite(session, item.id, value)
        }

    fun toggleFollowing() {
        val current = _uiState.value.data ?: return
        val previous = current.item
        if (previous.type !in setOf("Movie", "Series")) return
        viewModelScope.launch {
            val targetValue = !(previous.following ?: false)
            _uiState.value =
                _uiState.value.copy(
                    data = current.copy(item = previous.copy(following = targetValue)),
                    actionBusy = true,
                    actionError = false,
                )
            runCatching { repository.setFollowing(session, previous.id, targetValue) }
                .onFailure {
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                    _uiState.value =
                        _uiState.value.copy(
                            data = current,
                            actionBusy = false,
                            actionError = true,
                        )
                }
                .onSuccess { _uiState.value = _uiState.value.copy(actionBusy = false) }
        }
    }

    fun selectAudioTrack(streamIndex: Int) {
        val current = _uiState.value
        val source = current.trackSource ?: return
        if (source.mediaStreams.none { it.type.equals("audio", true) && it.index == streamIndex })
            return
        _uiState.value =
            current.copy(
                trackSelection =
                    (current.trackSelection ?: defaultTrackSelection(source)).copy(
                        audioStreamId = streamIndex
                    )
            )
    }

    fun selectSubtitleTrack(streamIndex: Int?) {
        val current = _uiState.value
        val source = current.trackSource ?: return
        if (
            streamIndex != null &&
                source.mediaStreams.none {
                    it.type.equals("subtitle", true) && it.index == streamIndex
                }
        )
            return
        _uiState.value =
            current.copy(
                trackSelection =
                    (current.trackSelection ?: defaultTrackSelection(source)).copy(
                        subtitleStreamIndex = streamIndex,
                        hasSubtitleSelection = true,
                    )
            )
    }

    fun playbackTrackSelection(): PlaybackTrackSelection? = _uiState.value.trackSelection

    fun searchBazarrSubtitles() {
        val current = _uiState.value
        val item = current.data?.item ?: return
        val sourceId = current.trackSource?.id ?: return
        if (item.type != "Episode") return
        val detailGeneration = loadGeneration
        val searchGeneration = ++bazarrSearchGeneration
        bazarrSearchJob?.cancel()
        bazarrSearchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(bazarrBusy = true, bazarrError = false, bazarrSearch = null)
            }
            runCatching { repository.searchBazarrSubtitles(session, item.id, sourceId) }
                .onSuccess { result ->
                    if (
                        searchGeneration != bazarrSearchGeneration ||
                            detailGeneration != loadGeneration ||
                            _uiState.value.data?.item?.id != item.id ||
                            _uiState.value.trackSource?.id != sourceId
                    ) {
                        return@onSuccess
                    }
                    _uiState.update { it.copy(bazarrBusy = false, bazarrSearch = result) }
                }
                .onFailure {
                    if (
                        searchGeneration != bazarrSearchGeneration ||
                            detailGeneration != loadGeneration ||
                            _uiState.value.data?.item?.id != item.id ||
                            _uiState.value.trackSource?.id != sourceId
                    ) {
                        return@onFailure
                    }
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                    _uiState.update { state -> state.copy(bazarrBusy = false, bazarrError = true) }
                }
        }
    }

    fun downloadBazarrSubtitle(matchId: String) {
        val current = _uiState.value
        val item = current.data?.item ?: return
        val sourceId = current.trackSource?.id ?: return
        if (item.type != "Episode" || matchId.isBlank()) return
        viewModelScope.launch {
            _uiState.value = current.copy(bazarrBusy = true, bazarrError = false)
            runCatching { repository.downloadBazarrSubtitle(session, item.id, sourceId, matchId) }
                .onSuccess {
                    _uiState.value =
                        _uiState.value.copy(
                            bazarrBusy = false,
                            bazarrSearch = null,
                            bazarrStatus =
                                _uiState.value.bazarrStatus?.copy(state = "download_started"),
                        )
                }
                .onFailure {
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                    _uiState.value = _uiState.value.copy(bazarrBusy = false, bazarrError = true)
                }
        }
    }

    private fun loadTrackSource(generation: Long, item: MediaItem) {
        trackLoadJob?.cancel()
        if (item.type !in setOf("Movie", "Episode")) return
        trackLoadJob = viewModelScope.launch {
            val source =
                runCatching { repository.playbackSource(session, item.id) }.getOrNull()
                    ?: return@launch
            if (generation != loadGeneration || _uiState.value.data?.item?.id != item.id)
                return@launch
            _uiState.value =
                _uiState.value.copy(
                    trackSource = source,
                    trackSelection =
                        defaultTrackSelection(
                            source,
                            runCatching { repository.loadPlaybackPreference() }.getOrNull(),
                        ),
                )
            if (item.type == "Episode" && source.id != null) {
                val bazarrStatus =
                    runCatching { repository.bazarrStatus(session, item.id, source.id) }.getOrNull()
                if (generation == loadGeneration && _uiState.value.data?.item?.id == item.id) {
                    _uiState.value = _uiState.value.copy(bazarrStatus = bazarrStatus)
                }
            }
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
            val optimistic =
                if (playedAction) {
                    previous.copy(played = targetValue)
                } else {
                    previous.copy(favorite = targetValue)
                }
            _uiState.value =
                _uiState.value.copy(
                    data = current.copy(item = optimistic),
                    actionBusy = true,
                    actionError = false,
                )
            runCatching { action(previous, targetValue) }
                .onFailure {
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                    _uiState.value =
                        _uiState.value.copy(
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
            val optimisticSeason =
                if (playedAction) {
                    previous.copy(played = targetValue)
                } else {
                    previous.copy(favorite = targetValue)
                }
            _uiState.value =
                _uiState.value.copy(
                    data =
                        current.copy(
                            seasons =
                                current.seasons.map { season ->
                                    if (season.id == seasonId) optimisticSeason else season
                                }
                        ),
                    actionBusy = true,
                    actionError = false,
                )
            runCatching { action(previous, targetValue) }
                .onFailure {
                    if ((it as? CatalogException)?.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                    _uiState.value =
                        _uiState.value.copy(
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

internal fun keepSelectedSeason(data: DetailData, requestedSeasonId: String?): DetailData {
    val availableSeasonIds = data.seasons.map { it.id }.toSet()
    val selectedSeasonId =
        requestedSeasonId?.takeIf { it in availableSeasonIds }
            ?: data.selectedSeasonId?.takeIf { it in availableSeasonIds }
            ?: data.seasons.firstOrNull { it.indexNumber == 1 }?.id
            ?: data.seasons.firstOrNull()?.id
    return data.copy(selectedSeasonId = selectedSeasonId)
}

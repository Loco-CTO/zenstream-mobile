package com.zenstream.zenstreammobile.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.FavoritesDataSource
import com.zenstream.zenstreammobile.data.LibraryDataSource
import com.zenstream.zenstreammobile.data.SearchDataSource
import com.zenstream.zenstreammobile.data.imageBlurHash
import com.zenstream.zenstreammobile.data.imageUrl
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.FavoriteSort
import com.zenstream.zenstreammobile.model.FavoriteSortBy
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.LibrarySortBy
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.SearchFilter
import com.zenstream.zenstreammobile.model.SortOrder
import com.zenstream.zenstreammobile.ui.FavoritesViewModel
import com.zenstream.zenstreammobile.ui.HomeViewModel
import com.zenstream.zenstreammobile.ui.LibraryViewModel
import com.zenstream.zenstreammobile.ui.SearchUiState
import com.zenstream.zenstreammobile.ui.SearchViewModel
import com.zenstream.zenstreammobile.ui.components.AudioCard
import com.zenstream.zenstreammobile.ui.components.BlurHashAsyncImage
import com.zenstream.zenstreammobile.ui.components.MediaRowView
import com.zenstream.zenstreammobile.ui.components.POSTER_CARD_MIN_WIDTH
import com.zenstream.zenstreammobile.ui.components.authenticatedImageRequest
import com.zenstream.zenstreammobile.ui.components.formatDurationSeconds
import com.zenstream.zenstreammobile.ui.components.itemSubtitle
import com.zenstream.zenstreammobile.ui.components.musicAlbumArtist
import com.zenstream.zenstreammobile.ui.components.musicReleaseYear
import com.zenstream.zenstreammobile.ui.components.musicSubtitle
import com.zenstream.zenstreammobile.ui.navigation.ChromeVisibilitySlot
import com.zenstream.zenstreammobile.ui.navigation.HIDE_DISTANCE_DP
import com.zenstream.zenstreammobile.ui.navigation.MainNavigationBar
import com.zenstream.zenstreammobile.ui.navigation.REVEAL_DISTANCE_DP
import com.zenstream.zenstreammobile.ui.navigation.ScrollVisibilityController

@Composable
fun HomeScreen(
    repository: CatalogRepository,
    session: AuthSession,
    padding: PaddingValues,
    onScrollabilityChanged: (Boolean) -> Unit = {},
    onItemClick: (MediaItem) -> Unit,
) {
    val vm: HomeViewModel =
        viewModel(
            key = "home-${session.userId}-${session.token}",
            factory = HomeViewModel.Factory(repository, session),
        )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    ObserveScrollability(
        canScroll = { listState.canScrollForward || listState.canScrollBackward },
        onScrollabilityChanged = onScrollabilityChanged,
    )
    when {
        state.error ->
            ErrorState(
                padding,
                R.string.library_load_failed,
                vm::load,
            )

        state.loading && state.data == null -> CenterLoading(padding)

        else -> {
            val data = state.data
            val homeRows = data?.rows.orEmpty().distinctBy { it.key }
            PullToRefreshLayout(
                isRefreshing = state.loading,
                onRefresh = vm::refresh,
                modifier = Modifier.padding(padding),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp),
                ) {
                    item {
                        FeaturedHero(
                            data?.featured.orEmpty(),
                            session,
                            showEmptyLibrary =
                                !state.loading &&
                                    data?.rows.isNullOrEmpty() &&
                                    data?.featured.isNullOrEmpty(),
                            onItemClick = onItemClick,
                        )
                    }
                    items(
                        homeRows,
                        key = { it.key },
                    ) { row ->
                        MediaRowView(
                            row,
                            session,
                            onItemClick,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)
@Composable
internal fun FeaturedHero(
    items: List<MediaItem>,
    session: AuthSession,
    showEmptyLibrary: Boolean,
    onItemClick: (MediaItem) -> Unit = {},
) {
    if (items.isEmpty()) {
        if (!showEmptyLibrary) {
            Spacer(Modifier.height(24.dp))
            return
        }
        Column(
            modifier = Modifier.fillMaxWidth().height(300.dp).padding(20.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(
                stringResource(R.string.empty_library),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.empty_library_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val pagerState = rememberPagerState(pageCount = { items.size })
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        val featureBarHeight = calculateFeatureBarHeight(maxWidth, screenHeightDp)
        Box(
            Modifier.fillMaxWidth()
                .height(featureBarHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = items[page]
                val openDescription =
                    stringResource(
                        R.string.open_details_description,
                        item.name,
                    )
                Box(
                    Modifier.fillMaxSize()
                        .clickable { onItemClick(item) }
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            contentDescription = openDescription
                        }
                ) {
                    val url = imageUrl(session.serverUrl, item, "Backdrop", 1280, 720)
                    val request = url?.let {
                        authenticatedImageRequest(LocalContext.current, it, session)
                    }
                    BlurHashAsyncImage(
                        model = request,
                        imageKey = url,
                        blurHash = imageBlurHash(item, "Backdrop"),
                        contentDescription =
                            stringResource(
                                R.string.backdrop_description,
                                item.name,
                            ),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(.58f),
                    )
                    Box(
                        Modifier.fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color(0xFF080808),
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val logoUrl = imageUrl(session.serverUrl, item, "Logo", 680, 260)
                        val logoRequest = logoUrl?.let {
                            authenticatedImageRequest(LocalContext.current, it, session)
                        }
                        if (logoRequest != null) {
                            BlurHashAsyncImage(
                                model = logoRequest,
                                imageKey = logoUrl,
                                blurHash = null,
                                contentDescription =
                                    stringResource(
                                        R.string.logo_description,
                                        item.name,
                                    ),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(260.dp, 72.dp).semantics { heading() },
                            )
                        } else {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.headlineLarge,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        Text(
                            itemSubtitle(item),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = .65f),
                        )
                    }
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(items.size) { index ->
            Box(
                Modifier.padding(horizontal = 3.dp)
                    .size(if (index == pagerState.currentPage) 18.dp else 6.dp, 4.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary
                        else Color.White.copy(alpha = .25f)
                    )
            )
        }
    }
}

internal const val FEATURE_BAR_ASPECT_RATIO = 16f / 9f
internal const val FEATURE_BAR_MAX_SCREEN_HEIGHT_FRACTION = 0.4f

internal fun featureBarMaxHeight(screenHeightDp: Int) =
    screenHeightDp.toFloat().dp * FEATURE_BAR_MAX_SCREEN_HEIGHT_FRACTION

internal fun calculateFeatureBarHeight(maxWidth: Dp, screenHeightDp: Int) =
    minOf(maxWidth / FEATURE_BAR_ASPECT_RATIO, featureBarMaxHeight(screenHeightDp))

@Composable
fun SearchOverlayScreen(
    repository: SearchDataSource,
    session: AuthSession,
    currentRoute: String,
    onDestinationClick: (String) -> Unit,
    onDismiss: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val vm: SearchViewModel =
        viewModel(
            key = "search-overlay-${session.userId}-${session.token}",
            factory = SearchViewModel.Factory(repository, session),
        )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val bottomBarVisibility =
        remember(density) {
            ScrollVisibilityController(
                hideDistance = with(density) { HIDE_DISTANCE_DP.dp.toPx() },
                revealDistance = with(density) { REVEAL_DISTANCE_DP.dp.toPx() },
            )
        }
    var bottomBarVisibilityFraction by remember { mutableStateOf(1f) }

    LaunchedEffect(Unit) {
        searchFocusRequester.requestFocus()
        bottomBarVisibilityFraction = bottomBarVisibility.resetForRoute()
    }

    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag("search-overlay-solid")
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("search-dialog"),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                ChromeVisibilitySlot(
                    visibilityFraction = bottomBarVisibilityFraction,
                    modifier =
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background),
                    collapseFromBottom = true,
                    applyNavigationBarsPadding = true,
                ) {
                    MainNavigationBar(
                        currentRoute = currentRoute,
                        session = session,
                        onDestinationClick = onDestinationClick,
                    )
                }
            },
        ) { padding ->
            SearchResultsContent(
                state = state,
                session = session,
                padding = padding,
                onQueryChange = vm::updateQuery,
                onFilterChange = vm::selectFilter,
                onRetry = {
                    focusManager.clearFocus()
                    vm.retry()
                },
                onLoadMore = vm::loadMore,
                onItemClick = { item ->
                    onDismiss()
                    onItemClick(item)
                },
                onNestedScroll = { consumedY, availableY, isScrollable ->
                    bottomBarVisibilityFraction =
                        bottomBarVisibility.onNestedScroll(
                            consumedY = consumedY,
                            availableY = availableY,
                            isScrollable = isScrollable,
                        )
                },
                onScrollabilityChanged = {},
                searchFieldModifier = Modifier.focusRequester(searchFocusRequester),
                showBackButton = true,
                onBack = onDismiss,
                modifier = Modifier.fillMaxSize().statusBarsPadding().padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun SearchResultsContent(
    state: SearchUiState,
    session: AuthSession,
    padding: PaddingValues,
    onQueryChange: (String) -> Unit,
    onFilterChange: (SearchFilter) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
    onNestedScroll: (consumedY: Float, availableY: Float, isScrollable: Boolean) -> Unit =
        { _, _, _ ->
        },
    onScrollabilityChanged: (Boolean) -> Unit = {},
    searchFieldModifier: Modifier = Modifier,
    showBackButton: Boolean = false,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val topBarVisibility =
        remember(density) {
            ScrollVisibilityController(
                hideDistance = with(density) { 56.dp.toPx() },
                revealDistance = with(density) { 64.dp.toPx() },
            )
        }
    var topBarVisibilityFraction by remember { mutableStateOf(1f) }
    val topBarScrollConnection =
        remember(listState) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    val isScrollable = listState.canScrollForward || listState.canScrollBackward
                    topBarVisibilityFraction =
                        topBarVisibility.onNestedScroll(
                            consumedY = consumed.y,
                            availableY = available.y,
                            isScrollable = isScrollable,
                        )
                    onNestedScroll(consumed.y, available.y, isScrollable)
                    return Offset.Zero
                }
            }
        }
    LaunchedEffect(Unit) {
        topBarVisibilityFraction = topBarVisibility.resetForRoute()
    }
    LaunchedEffect(
        listState,
        state.results.size,
        state.totalRecordCount,
        state.loading,
        state.loadingMore,
        state.loadMoreError,
        state.error,
        onLoadMore,
    ) {
        snapshotFlowLastVisibleIndex(listState).collect { lastVisible ->
            if (
                lastVisible >= 0 &&
                    lastVisible >= state.results.size - 4 &&
                    state.results.size < state.totalRecordCount &&
                    !state.loading &&
                    !state.loadingMore &&
                    !state.loadMoreError &&
                    !state.error
            ) {
                onLoadMore()
            }
        }
    }
    ObserveScrollability(
        canScroll = { listState.canScrollForward || listState.canScrollBackward },
        onScrollabilityChanged = { isScrollable -> onScrollabilityChanged(isScrollable) },
    )
    Column(modifier.fillMaxSize().padding(padding)) {
        ChromeVisibilitySlot(
            visibilityFraction = topBarVisibilityFraction,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                if (showBackButton) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(
                                painter = painterResource(LucideR.drawable.lucide_ic_arrow_left),
                                contentDescription = stringResource(R.string.back),
                                modifier = Modifier.size(32.dp),
                            )
                        }
                        SearchField(
                            value = state.query,
                            onValueChange = onQueryChange,
                            onSubmit = onRetry,
                            onClear = { onQueryChange("") },
                            compact = true,
                            modifier = Modifier.weight(1f).height(56.dp).then(searchFieldModifier),
                        )
                    }
                } else {
                    SearchField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        onSubmit = onRetry,
                        onClear = { onQueryChange("") },
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .then(searchFieldModifier),
                    )
                }
                if (state.resultQuery.isNotEmpty()) {
                    Text(
                        stringResource(R.string.search_result_count, state.totalRecordCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                    SearchFilterPills(state = state, onFilterChange = onFilterChange)
                }
            }
        }
        Box(Modifier.weight(1f).nestedScroll(topBarScrollConnection)) {
            when {
                state.error && state.results.isNotEmpty() ->
                    Column(Modifier.fillMaxSize()) {
                        SearchErrorBanner(onRetry)
                        SearchResultsList(
                            items = state.results,
                            listState = listState,
                            session = session,
                            loadingMore = state.loadingMore,
                            loadMoreError = state.loadMoreError,
                            onLoadMore = onLoadMore,
                            onItemClick = onItemClick,
                            modifier = Modifier.weight(1f),
                        )
                    }

                state.error -> ErrorState(PaddingValues(), R.string.search_load_failed, onRetry)
                state.loading && state.results.isEmpty() && state.query.trim().isNotEmpty() ->
                    CenterLoading(PaddingValues())

                state.resultQuery.isEmpty() -> Unit

                state.results.isEmpty() ->
                    Text(
                        stringResource(R.string.no_search_results),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )

                else ->
                    SearchResultsList(
                        items = state.results,
                        listState = listState,
                        session = session,
                        loadingMore = state.loadingMore,
                        loadMoreError = state.loadMoreError,
                        onLoadMore = onLoadMore,
                        onItemClick = onItemClick,
                        modifier = Modifier.fillMaxSize(),
                    )
            }
        }
    }
}

@Composable
private fun SearchFilterPills(
    state: SearchUiState,
    onFilterChange: (SearchFilter) -> Unit,
) {
    val filters =
        listOf(
                SearchFilter.All,
                SearchFilter.Series,
                SearchFilter.Movie,
                SearchFilter.Release,
                SearchFilter.Artist,
                SearchFilter.Track,
                SearchFilter.Collection,
            )
            .filter { filter -> filter == SearchFilter.All || state.facets.count(filter) > 0 }
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag("search-filter-row"),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(filters, key = { it.name }) { filter ->
            val count =
                if (filter == SearchFilter.All && state.facets.all == 0) {
                    state.totalRecordCount
                } else {
                    state.facets.count(filter)
                }
            FilterChip(
                selected = state.selectedFilter == filter,
                onClick = { onFilterChange(filter) },
                label = {
                    Text(
                        text = "${stringResource(searchFilterLabel(filter))} $count",
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun SearchResultsList(
    items: List<MediaItem>,
    listState: LazyListState,
    session: AuthSession,
    loadingMore: Boolean,
    loadMoreError: Boolean,
    onLoadMore: () -> Unit,
    onItemClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val featured = items.firstOrNull { item ->
        item.type in setOf("Movie", "Series", "BoxSet", "Collection") &&
            item.backdropImageTags.isNotEmpty() &&
            imageUrl(session.serverUrl, item, "Backdrop", 1_280, 720) != null
    }
    val visibleItems = items.distinctBy { it.id }.filterNot { item -> item.id == featured?.id }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = 20.dp),
        modifier = modifier.testTag("search-results-list"),
    ) {
        featured?.let { item ->
            item(key = "search-featured-${item.id}") {
                SearchFeaturedPanel(
                    item = item,
                    session = session,
                    onItemClick = onItemClick,
                )
            }
        }
        items(visibleItems, key = { "search-result-${it.id}" }) { item ->
            SearchResultRow(
                item = item,
                session = session,
                onItemClick = onItemClick,
            )
        }
        if (loadingMore) {
            item(key = "search-loading-more") {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
        if (loadMoreError) {
            item(key = "search-load-more-error") {
                InlineLoadMoreError(onRetry = onLoadMore)
            }
        }
    }
}

@Composable
private fun SearchFeaturedPanel(
    item: MediaItem,
    session: AuthSession,
    onItemClick: (MediaItem) -> Unit,
) {
    val url = imageUrl(session.serverUrl, item, "Backdrop", 1_280, 720)
    val request = url?.let { authenticatedImageRequest(LocalContext.current, it, session) }
    val actionDescription = searchItemActionDescription(item)
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().heightIn(min = 184.dp).padding(16.dp, 8.dp, 16.dp, 12.dp)
    ) {
        val ratio = if (maxWidth >= 600.dp) 3f else 16f / 9f
        Box(
            modifier =
                Modifier.fillMaxWidth()
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) { onItemClick(item) }
                    .semantics {
                        role = Role.Button
                        contentDescription = actionDescription
                    }
                    .testTag("search-featured-panel")
        ) {
            BlurHashAsyncImage(
                model = request,
                imageKey = url,
                blurHash = imageUrlBlurHash(item),
                contentDescription = stringResource(R.string.backdrop_description, item.name),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = .86f))
                        )
                    )
            )
            Box(
                Modifier.fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Black.copy(alpha = .45f), Color.Transparent)
                        )
                    )
            )
            Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                Text(
                    stringResource(searchFilterLabel(searchFilterForItem(item))),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = .78f),
                )
                Text(
                    item.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.productionYear?.let {
                    Text(
                        it.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = .72f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    item: MediaItem,
    session: AuthSession,
    onItemClick: (MediaItem) -> Unit,
) {
    val isMusic = item.type in setOf("MusicAlbum", "MusicArtist", "Audio")
    val imageWidth = 64.dp
    val imageUrl =
        imageUrl(
            session.serverUrl,
            item,
            "Primary",
            if (isMusic) 320 else 280,
            if (isMusic) 320 else 420,
        )
    val request = imageUrl?.let { authenticatedImageRequest(LocalContext.current, it, session) }
    val actionDescription = searchItemActionDescription(item)
    val typeLabel = stringResource(searchFilterLabel(searchFilterForItem(item)))
    val secondary = searchItemSecondary(item)
    val year = if (isMusic) musicReleaseYear(item) else item.productionYear?.toString()
    val duration =
        when {
            item.durationSeconds != null && item.durationSeconds > 0 ->
                formatDurationSeconds(item.durationSeconds)
            item.runtimeTicks != null && item.runtimeTicks > 0 ->
                formatDurationSeconds(item.runtimeTicks / 10_000_000.0)
            else -> null
        }
    val metadata = listOfNotNull(year, duration).joinToString(" · ")
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button) { onItemClick(item) }
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = actionDescription
                }
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(min = if (isMusic) 80.dp else 112.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("search-result-row-${item.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier.width(imageWidth)
                        .aspectRatio(if (isMusic) 1f else 2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .testTag("search-artwork-${item.id}")
            ) {
                BlurHashAsyncImage(
                    model = request,
                    imageKey = imageUrl,
                    blurHash = imageUrl?.let { imageBlurHashForSearch(item) },
                    contentDescription = stringResource(R.string.poster_description, item.name),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (secondary.isNotBlank()) {
                    Text(
                        secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier =
                            Modifier.clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    if (metadata.isNotBlank()) {
                        Text(
                            metadata,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = .08f))
        )
    }
}

@Composable
private fun searchItemSecondary(item: MediaItem): String =
    when (item.type) {
        "MusicArtist" -> musicSubtitle(item)
        "MusicAlbum" -> musicAlbumArtist(item) ?: musicSubtitle(item)
        "Audio" -> listOfNotNull(musicAlbumArtist(item), item.album).joinToString(" · ")
        "Series",
        "Movie",
        "BoxSet",
        "Collection" -> itemSubtitle(item)
        else -> itemSubtitle(item)
    }

private fun searchFilterForItem(item: MediaItem): SearchFilter =
    when (item.type) {
        "Series" -> SearchFilter.Series
        "Movie" -> SearchFilter.Movie
        "BoxSet",
        "Collection" -> SearchFilter.Collection
        "MusicAlbum" -> SearchFilter.Release
        "MusicArtist" -> SearchFilter.Artist
        "Audio" -> SearchFilter.Track
        else -> SearchFilter.All
    }

private fun searchFilterLabel(filter: SearchFilter): Int =
    when (filter) {
        SearchFilter.All -> R.string.search_filter_all
        SearchFilter.Series -> R.string.search_filter_series
        SearchFilter.Movie -> R.string.search_filter_movie
        SearchFilter.Collection -> R.string.search_filter_collection
        SearchFilter.Release -> R.string.search_filter_release
        SearchFilter.Artist -> R.string.search_filter_artist
        SearchFilter.Track -> R.string.search_filter_track
    }

@Composable
private fun searchItemActionDescription(item: MediaItem): String =
    if (item.type in setOf("BoxSet", "Collection", "MusicArtist", "MusicAlbum", "Audio")) {
        stringResource(R.string.open_details_description, item.name)
    } else {
        stringResource(R.string.play_description, item.name)
    }

private fun imageUrlBlurHash(item: MediaItem): String? = imageBlurHash(item, "Backdrop")

private fun imageBlurHashForSearch(item: MediaItem): String? = imageBlurHash(item, "Primary")

@Composable
private fun SearchErrorBanner(onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.search_load_failed),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        leadingIcon =
            if (compact) {
                null
            } else {
                {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_search),
                        contentDescription = null,
                    )
                }
            },
        prefix =
            if (compact) {
                { Spacer(Modifier.width(4.dp)) }
            } else {
                null
            },
        trailingIcon = {
            if (value.isNotEmpty())
                IconButton(onClick = onClear) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_x),
                        contentDescription = stringResource(R.string.close),
                    )
                }
        },
        placeholder = { Text(stringResource(R.string.search_placeholder)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        colors =
            if (compact) {
                OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF252525),
                    unfocusedContainerColor = Color(0xFF252525),
                    disabledContainerColor = Color(0xFF252525),
                    errorContainerColor = Color(0xFF252525),
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    errorBorderColor = Color.Transparent,
                )
            } else {
                OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                )
            },
        shape = if (compact) RoundedCornerShape(50) else RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth().then(modifier),
    )
}

@Composable
fun FavoritesScreen(
    repository: FavoritesDataSource,
    session: AuthSession,
    padding: PaddingValues,
    onScrollabilityChanged: (Boolean) -> Unit = {},
    onItemClick: (MediaItem) -> Unit,
    onPlayTracks: (List<MediaItem>, Int, Boolean?, Boolean) -> Unit = { _, _, _, _ -> },
) {
    var selectedTab by remember(session.userId, session.token) { mutableIntStateOf(0) }
    val tabs = listOf(R.string.watchlist, R.string.favorites, R.string.playlists)
    Column(Modifier.fillMaxSize().padding(padding)) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(stringResource(title)) },
                )
            }
        }
        when (selectedTab) {
            0 -> WatchlistContent(repository, session, onItemClick, onScrollabilityChanged)
            1 ->
                FavoritesTabContent(
                    repository,
                    session,
                    PaddingValues(),
                    onScrollabilityChanged,
                    onItemClick,
                )
            else ->
                PlaylistLibraryContent(repository, session, onPlayTracks, onScrollabilityChanged)
        }
    }
}

@Composable
private fun FavoritesTabContent(
    repository: FavoritesDataSource,
    session: AuthSession,
    padding: PaddingValues,
    onScrollabilityChanged: (Boolean) -> Unit = {},
    onItemClick: (MediaItem) -> Unit,
) {
    val vm: FavoritesViewModel =
        viewModel(
            key = "favorites-${session.userId}-${session.token}",
            factory = FavoritesViewModel.Factory(repository, session),
        )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val episodes = state.items.filter { it.type.equals("Episode", ignoreCase = true) }
    val movies = state.items.filter { it.type.equals("Movie", ignoreCase = true) }
    val series = state.items.filter { it.type.equals("Series", ignoreCase = true) }
    val favoriteArtists = state.items.filter { it.type == "MusicArtist" }
    val favoriteAlbums = state.items.filter { it.type == "MusicAlbum" }
    val favoriteTracks = state.items.filter { it.type == "Audio" }
    val listState = rememberLazyListState()
    ObserveScrollability(
        canScroll = { listState.canScrollForward || listState.canScrollBackward },
        onScrollabilityChanged = onScrollabilityChanged,
    )
    Column(Modifier.fillMaxSize().padding(padding)) {
        FavoritesHeader(state.sort, state.totalRecordCount, vm::setSort)
        PullToRefreshLayout(
            isRefreshing = shouldShowPullToRefresh(state.loading, state.items.isNotEmpty()),
            onRefresh = vm::refresh,
            modifier = Modifier.weight(1f),
        ) {
            when {
                state.loading && state.items.isEmpty() -> CenterLoading(PaddingValues())
                state.error && state.items.isEmpty() ->
                    ErrorState(PaddingValues(), R.string.favorites_load_failed, vm::refresh)
                !state.loading && state.items.isEmpty() ->
                    EmptyState(
                        stringResource(R.string.no_favorites),
                        stringResource(R.string.no_favorites_hint),
                    )
                else ->
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = 20.dp),
                    ) {
                        if (favoriteArtists.isNotEmpty()) {
                            item(key = "favorite-artists") {
                                FavoriteMusicSection(
                                    title = stringResource(R.string.favorite_artists),
                                    items = favoriteArtists,
                                    session = session,
                                    onItemClick = onItemClick,
                                )
                            }
                        }
                        if (favoriteAlbums.isNotEmpty()) {
                            item(key = "favorite-albums") {
                                FavoriteMusicSection(
                                    title = stringResource(R.string.favorite_albums),
                                    items = favoriteAlbums,
                                    session = session,
                                    onItemClick = onItemClick,
                                )
                            }
                        }
                        if (favoriteTracks.isNotEmpty()) {
                            item(key = "favorite-tracks") {
                                FavoriteMusicSection(
                                    title = stringResource(R.string.favorite_tracks),
                                    items = favoriteTracks,
                                    session = session,
                                    onItemClick = onItemClick,
                                )
                            }
                        }
                        if (episodes.isNotEmpty()) {
                            item(key = "favorite-episodes") {
                                FavoriteSection(
                                    R.string.favorite_episodes,
                                    episodes,
                                    session,
                                    wide = true,
                                    onItemClick = onItemClick,
                                )
                            }
                        }
                        if (movies.isNotEmpty()) {
                            item(key = "favorite-movies") {
                                FavoriteSection(
                                    R.string.favorite_movies,
                                    movies,
                                    session,
                                    wide = false,
                                    onItemClick = onItemClick,
                                )
                            }
                        }
                        if (series.isNotEmpty()) {
                            item(key = "favorite-series") {
                                FavoriteSection(
                                    R.string.favorite_series,
                                    series,
                                    session,
                                    wide = false,
                                    onItemClick,
                                )
                            }
                        }
                        if (state.loadingMore) {
                            item(key = "favorites-loading-more") {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                        if (state.loadMoreError) {
                            item(key = "favorites-load-more-error") {
                                InlineLoadMoreError(onRetry = vm::refresh)
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun FavoritesHeader(
    sort: FavoriteSort,
    total: Int,
    onSortChanged: (FavoriteSort) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.favorites),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.favorite_item_count, total),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = {
                onSortChanged(
                    sort.copy(
                        sortOrder =
                            if (sort.sortOrder == SortOrder.Ascending) SortOrder.Descending
                            else SortOrder.Ascending
                    )
                )
            }
        ) {
            Icon(
                painter =
                    painterResource(
                        if (sort.sortOrder == SortOrder.Ascending)
                            LucideR.drawable.lucide_ic_arrow_up
                        else LucideR.drawable.lucide_ic_arrow_down
                    ),
                contentDescription =
                    stringResource(
                        if (sort.sortOrder == SortOrder.Ascending) R.string.sort_descending
                        else R.string.sort_ascending
                    ),
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(
                    painter = painterResource(LucideR.drawable.lucide_ic_list_filter),
                    contentDescription = stringResource(R.string.sort_by),
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                FavoriteSortBy.entries.forEach { sortBy ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                when (sortBy) {
                                    FavoriteSortBy.Title -> stringResource(R.string.sort_title)
                                    FavoriteSortBy.DateAdded ->
                                        stringResource(R.string.sort_date_added)
                                }
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onSortChanged(sort.copy(sortBy = sortBy))
                        },
                        leadingIcon =
                            if (sortBy == sort.sortBy) {
                                { Icon(painterResource(LucideR.drawable.lucide_ic_check), null) }
                            } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteSection(
    title: Int,
    items: List<MediaItem>,
    session: AuthSession,
    wide: Boolean,
    onItemClick: (MediaItem) -> Unit,
) {
    val uniqueItems = items.distinctBy { it.id }
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(uniqueItems, key = { it.id }) { item ->
                com.zenstream.zenstreammobile.ui.components.MediaCard(
                    item = item,
                    session = session,
                    wide = wide,
                    onClick = onItemClick,
                    gridCard = false,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FavoriteMusicSection(
    title: String,
    items: List<MediaItem>,
    session: AuthSession,
    onItemClick: (MediaItem) -> Unit,
) {
    val uniqueItems = items.distinctBy { it.id }
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(uniqueItems, key = { it.id }) { item ->
                AudioCard(item = item, session = session, onClick = onItemClick)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun LibraryScreen(
    repository: LibraryDataSource,
    session: AuthSession,
    padding: PaddingValues,
    onScrollabilityChanged: (Boolean) -> Unit = {},
    onItemClick: (MediaItem) -> Unit,
) {
    val vm: LibraryViewModel =
        viewModel(
            key = "library-${session.userId}-${session.token}",
            factory = LibraryViewModel.Factory(repository, session),
        )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val density = LocalDensity.current
    val topBarVisibility =
        remember(density) {
            ScrollVisibilityController(
                hideDistance = with(density) { 56.dp.toPx() },
                revealDistance = with(density) { 64.dp.toPx() },
            )
        }
    var topBarVisibilityFraction by remember { mutableStateOf(1f) }
    val topBarScrollConnection =
        remember(gridState) {
            object : NestedScrollConnection {
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    topBarVisibilityFraction =
                        topBarVisibility.onNestedScroll(
                            consumedY = consumed.y,
                            availableY = available.y,
                            isScrollable =
                                gridState.canScrollForward || gridState.canScrollBackward,
                        )
                    return Offset.Zero
                }
            }
        }
    LaunchedEffect(Unit) {
        topBarVisibilityFraction = topBarVisibility.resetForRoute()
    }
    ObserveScrollability(
        canScroll = { gridState.canScrollForward || gridState.canScrollBackward },
        onScrollabilityChanged = { isScrollable -> onScrollabilityChanged(isScrollable) },
    )
    LaunchedEffect(
        gridState,
        state.items.size,
        state.totalRecordCount,
        state.loading,
        state.loadingMore,
    ) {
        snapshotFlowLastVisibleIndex(gridState).collect { lastVisible ->
            if (
                lastVisible >= 0 &&
                    lastVisible >= state.items.size - 4 &&
                    state.items.size < state.totalRecordCount &&
                    !state.loading &&
                    !state.loadingMore
            ) {
                vm.loadMore()
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(padding)) {
        ChromeVisibilitySlot(
            visibilityFraction = topBarVisibilityFraction,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                if (state.libraries.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            state.libraries,
                            key = { it.id },
                        ) { library ->
                            FilterChip(
                                selected = state.selected?.id == library.id,
                                onClick = { vm.select(library) },
                                label = { Text(library.name) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                LibraryHeader(state = state, onSortChanged = vm::setSort)
            }
        }
        PullToRefreshLayout(
            isRefreshing = shouldShowPullToRefresh(state.loading, state.items.isNotEmpty()),
            onRefresh = vm::refresh,
            modifier = Modifier.weight(1f).nestedScroll(topBarScrollConnection),
        ) {
            when {
                state.loading && state.items.isEmpty() -> CenterLoading(PaddingValues())
                state.error && state.items.isEmpty() ->
                    ErrorState(
                        PaddingValues(),
                        R.string.library_load_page_failed,
                        { vm.loadLibraries(state.selected?.id) },
                    )

                !state.loading && state.libraries.isEmpty() ->
                    EmptyState(
                        stringResource(R.string.no_libraries),
                        stringResource(R.string.no_libraries_hint),
                    )

                !state.loading && state.items.isEmpty() ->
                    EmptyState(
                        stringResource(R.string.empty_library),
                        stringResource(R.string.empty_library_hint),
                    )

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = POSTER_CARD_MIN_WIDTH),
                        state = gridState,
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(state.items.distinctBy { it.id }, key = { it.id }) { item ->
                            Box(
                                Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                LibraryPosterCard(
                                    item,
                                    session,
                                    onItemClick,
                                )
                            }
                        }
                        if (state.loadingMore) {
                            item(
                                key = "library-loading-more",
                                span = {
                                    androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                                },
                            ) {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                        if (state.loadMoreError) {
                            item(
                                key = "library-load-more-error",
                                span = {
                                    androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan)
                                },
                            ) {
                                InlineLoadMoreError(onRetry = vm::loadMore)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryHeader(
    state: com.zenstream.zenstreammobile.ui.LibraryUiState,
    onSortChanged: (LibrarySort) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                state.selected?.name ?: stringResource(R.string.library),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(R.string.library_item_count, state.totalRecordCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(
            onClick = {
                onSortChanged(
                    state.sort.copy(
                        sortOrder =
                            if (state.sort.sortOrder == SortOrder.Ascending) {
                                SortOrder.Descending
                            } else {
                                SortOrder.Ascending
                            }
                    )
                )
            },
            enabled = state.selected != null,
        ) {
            Icon(
                painter =
                    painterResource(
                        if (state.sort.sortOrder == SortOrder.Ascending) {
                            LucideR.drawable.lucide_ic_arrow_up
                        } else {
                            LucideR.drawable.lucide_ic_arrow_down
                        }
                    ),
                contentDescription =
                    stringResource(
                        if (state.sort.sortOrder == SortOrder.Ascending) R.string.sort_descending
                        else R.string.sort_ascending
                    ),
            )
        }
        Box {
            IconButton(onClick = { menuExpanded = true }, enabled = state.selected != null) {
                Icon(
                    painter = painterResource(LucideR.drawable.lucide_ic_list_filter),
                    contentDescription = stringResource(R.string.sort_by),
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                val sortOptions =
                    if (state.selected?.collectionType == "music") {
                        listOf(LibrarySortBy.Title, LibrarySortBy.Year, LibrarySortBy.Added)
                    } else {
                        LibrarySortBy.entries.filter {
                            it != LibrarySortBy.LastAdded ||
                                state.selected?.supportsLastAdded == true
                        }
                    }
                sortOptions.forEach { sortBy ->
                    DropdownMenuItem(
                        text = { Text(sortLabel(sortBy)) },
                        onClick = {
                            menuExpanded = false
                            onSortChanged(state.sort.copy(sortBy = sortBy))
                        },
                        leadingIcon =
                            if (sortBy == state.sort.sortBy) {
                                {
                                    Icon(
                                        painterResource(LucideR.drawable.lucide_ic_check),
                                        contentDescription = null,
                                    )
                                }
                            } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun sortLabel(sortBy: LibrarySortBy): String =
    when (sortBy) {
        LibrarySortBy.Rating -> stringResource(R.string.sort_rating)
        LibrarySortBy.Title -> stringResource(R.string.sort_title)
        LibrarySortBy.Added -> stringResource(R.string.sort_date_added)
        LibrarySortBy.LastAdded -> stringResource(R.string.sort_last_added)
        LibrarySortBy.Release -> stringResource(R.string.sort_release_date)
        LibrarySortBy.Runtime -> stringResource(R.string.sort_runtime)
        LibrarySortBy.Year -> stringResource(R.string.sort_year)
    }

@Composable
private fun EmptyState(title: String, detail: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun InlineLoadMoreError(onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.library_load_more_failed),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun LibraryPosterCard(
    item: MediaItem,
    session: AuthSession,
    onItemClick: (MediaItem) -> Unit,
) {
    if (item.type in setOf("MusicAlbum", "MusicArtist", "Audio")) {
        AudioCard(
            item = item,
            session = session,
            onClick = onItemClick,
            width = null,
        )
    } else {
        com.zenstream.zenstreammobile.ui.components.MediaCard(
            item = item,
            session = session,
            wide = false,
            onClick = onItemClick,
            showRating = true,
            gridCard = true,
        )
    }
}

private fun snapshotFlowLastVisibleIndex(gridState: LazyGridState) = snapshotFlow {
    gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
}

private fun snapshotFlowLastVisibleIndex(listState: LazyListState) = snapshotFlow {
    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PlaybackPlaceholderScreen(itemName: String, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        itemName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(LucideR.drawable.lucide_ic_arrow_left),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_play),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.media_playback_failed),
                style = MaterialTheme.typography.titleLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CenterLoading(padding: PaddingValues) {
    Box(
        Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ErrorState(padding: PaddingValues, message: Int, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Icon(
                painterResource(LucideR.drawable.lucide_ic_refresh_cw),
                contentDescription = null,
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.retry))
        }
    }
}

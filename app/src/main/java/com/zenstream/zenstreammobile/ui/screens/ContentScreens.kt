package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import com.zenstream.zenstreammobile.data.LibraryDataSource
import com.zenstream.zenstreammobile.data.SearchDataSource
import com.zenstream.zenstreammobile.data.imageBlurHash
import com.zenstream.zenstreammobile.data.imageUrl
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.LibrarySort
import com.zenstream.zenstreammobile.model.LibrarySortBy
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.model.SortOrder
import com.zenstream.zenstreammobile.ui.HomeViewModel
import com.zenstream.zenstreammobile.ui.LibraryViewModel
import com.zenstream.zenstreammobile.ui.SearchViewModel
import com.zenstream.zenstreammobile.ui.components.BlurHashAsyncImage
import com.zenstream.zenstreammobile.ui.components.MediaRowView
import com.zenstream.zenstreammobile.ui.components.POSTER_CARD_MIN_WIDTH
import com.zenstream.zenstreammobile.ui.components.authenticatedImageRequest
import com.zenstream.zenstreammobile.ui.components.itemSubtitle
import com.zenstream.zenstreammobile.ui.navigation.ScrollVisibilityController

@Composable
fun HomeScreen(
    repository: CatalogRepository,
    session: AuthSession,
    padding: PaddingValues,
    onItemClick: (MediaItem) -> Unit,
) {
    val vm: HomeViewModel =
        viewModel(
            key = "home-${session.userId}",
            factory = HomeViewModel.Factory(repository, session),
        )
    val state by vm.uiState.collectAsStateWithLifecycle()
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
            PullToRefreshLayout(
                isRefreshing = state.loading,
                onRefresh = vm::refresh,
                modifier = Modifier.padding(padding),
            ) {
                LazyColumn(
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
                        data?.rows.orEmpty(),
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
fun SearchScreen(
    repository: SearchDataSource,
    session: AuthSession,
    padding: PaddingValues,
    onItemClick: (MediaItem) -> Unit,
) {
    val vm: SearchViewModel =
        viewModel(
            key = "search-${session.userId}",
            factory = SearchViewModel.Factory(repository, session),
        )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    val topBarVisibility =
        remember(density) {
            ScrollVisibilityController(
                hideDistance = with(density) { 56.dp.toPx() },
                revealDistance = with(density) { 64.dp.toPx() },
            )
        }
    var topBarVisible by remember { mutableStateOf(true) }
    val topBarScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                topBarVisible =
                    topBarVisibility.onNestedScroll(
                        consumedY = consumed.y,
                        availableY = available.y,
                    )
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(Unit) {
        topBarVisible = topBarVisibility.resetForRoute()
    }
    Column(Modifier.fillMaxSize().padding(padding)) {
        AnimatedVisibility(
            visible = topBarVisible,
            enter =
                expandVertically(expandFrom = Alignment.Top) +
                    slideInVertically(initialOffsetY = { -it }) +
                    fadeIn(),
            exit =
                shrinkVertically(shrinkTowards = Alignment.Top) +
                    slideOutVertically(targetOffsetY = { -it }) +
                    fadeOut(),
        ) {
            Column {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = vm::updateQuery,
                    leadingIcon = {
                        Icon(
                            painterResource(LucideR.drawable.lucide_ic_search),
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        if (state.query.isNotEmpty())
                            IconButton(
                                onClick = {
                                    vm.updateQuery("")
                                }
                            ) {
                                Icon(
                                    painter = painterResource(LucideR.drawable.lucide_ic_x),
                                    contentDescription = stringResource(R.string.close),
                                )
                            }
                    },
                    placeholder = { Text(stringResource(R.string.search_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.retry() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                if (state.query.trim().length >= 2 && !state.loading && !state.error) {
                    Text(
                        stringResource(R.string.search_result_count, state.results.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
        PullToRefreshLayout(
            isRefreshing = shouldShowPullToRefresh(state.loading, state.results.isNotEmpty()),
            onRefresh = vm::refresh,
            modifier = Modifier.weight(1f).nestedScroll(topBarScrollConnection),
        ) {
            when {
                state.loading && state.results.isEmpty() -> CenterLoading(PaddingValues())
                state.error -> ErrorState(PaddingValues(), R.string.search_load_failed, vm::retry)
                state.query.trim().length < 2 -> Unit

                state.results.isEmpty() ->
                    Text(
                        stringResource(R.string.no_search_results),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )

                else ->
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = POSTER_CARD_MIN_WIDTH),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(state.results, key = { it.id }) { item ->
                            Box(
                                Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                MediaCardForSearch(item, session, onItemClick)
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun MediaCardForSearch(
    item: MediaItem,
    session: AuthSession,
    onItemClick: (MediaItem) -> Unit,
) {
    com.zenstream.zenstreammobile.ui.components.MediaCard(
        item,
        session,
        wide = false,
        onClick = onItemClick,
        gridCard = true,
    )
}

@Composable
fun LibraryScreen(
    repository: LibraryDataSource,
    session: AuthSession,
    padding: PaddingValues,
    onItemClick: (MediaItem) -> Unit,
) {
    val vm: LibraryViewModel =
        viewModel(
            key = "library-${session.userId}",
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
    var topBarVisible by remember { mutableStateOf(true) }
    val topBarScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                topBarVisible =
                    topBarVisibility.onNestedScroll(
                        consumedY = consumed.y,
                        availableY = available.y,
                    )
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(Unit) {
        topBarVisible = topBarVisibility.resetForRoute()
    }
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
        AnimatedVisibility(
            visible = topBarVisible,
            enter =
                expandVertically(expandFrom = Alignment.Top) +
                    slideInVertically(initialOffsetY = { -it }) +
                    fadeIn(),
            exit =
                shrinkVertically(shrinkTowards = Alignment.Top) +
                    slideOutVertically(targetOffsetY = { -it }) +
                    fadeOut(),
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
                        items(state.items, key = { it.id }) { item ->
                            Box(
                                Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                LibraryPosterCard(item, session, onItemClick)
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
                LibrarySortBy.entries
                    .filter {
                        it != LibrarySortBy.LastAdded || state.selected?.supportsLastAdded == true
                    }
                    .forEach { sortBy ->
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
    com.zenstream.zenstreammobile.ui.components.MediaCard(
        item = item,
        session = session,
        wide = false,
        onClick = onItemClick,
        showRating = true,
        gridCard = true,
    )
}

private fun snapshotFlowLastVisibleIndex(gridState: LazyGridState) = snapshotFlow {
    gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
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

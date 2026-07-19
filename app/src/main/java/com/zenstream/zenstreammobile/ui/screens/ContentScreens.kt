package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.JellyfinApi
import com.zenstream.zenstreammobile.data.JellyfinRepository
import com.zenstream.zenstreammobile.data.imageUrl
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaItem
import com.zenstream.zenstreammobile.ui.HomeViewModel
import com.zenstream.zenstreammobile.ui.LibraryViewModel
import com.zenstream.zenstreammobile.ui.SearchViewModel
import com.zenstream.zenstreammobile.ui.components.MediaRowView
import com.zenstream.zenstreammobile.ui.components.itemSubtitle

@Composable
fun HomeScreen(
    repository: JellyfinRepository,
    session: AuthSession,
    padding: PaddingValues,
    onPlay: (MediaItem) -> Unit
) {
    val vm: HomeViewModel = viewModel(
        key = "home-${session.userId}",
        factory = HomeViewModel.Factory(repository, session)
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    when {
        state.loading && state.data == null -> CenterLoading(padding)
        state.error && state.data == null -> ErrorState(padding, R.string.library_load_failed, vm::load)
        else -> {
            val data = state.data
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 20.dp),
            ) {
                item {
                    FeaturedHero(
                        data?.featured.orEmpty(),
                        session,
                        showEmptyLibrary = data?.rows.isNullOrEmpty() && data?.featured.isNullOrEmpty(),
                    )
                }
                items(
                    data?.rows.orEmpty(),
                    key = { "${it.title}:${it.libraryName}" }) { row ->
                    MediaRowView(
                        row,
                        session,
                        onPlay
                    )
                }
            }
        }
    }
}

@OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
@Composable
internal fun FeaturedHero(
    items: List<MediaItem>,
    session: AuthSession,
    showEmptyLibrary: Boolean,
) {
    if (items.isEmpty()) {
        if (!showEmptyLibrary) {
            Spacer(Modifier.height(24.dp))
            return
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(20.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                stringResource(R.string.empty_library),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() })
            Text(
                stringResource(R.string.empty_library_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val pagerState = rememberPagerState(pageCount = { items.size })
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .aspectRatio(FEATURE_BAR_ASPECT_RATIO)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            Box(Modifier.fillMaxSize()) {
                val url = imageUrl(session.serverUrl, item, "Backdrop", 1280, 720)
                val request = url?.let {
                    ImageRequest.Builder(LocalContext.current).data(it).httpHeaders(
                        NetworkHeaders.Builder()
                            .set("Authorization", JellyfinApi.authorizationHeader(session.token))
                            .build()
                    ).crossfade(true).build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = stringResource(R.string.backdrop_description, item.name),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(.58f)
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color(0xFF080808)
                                )
                            )
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                    .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val logoUrl = imageUrl(session.serverUrl, item, "Logo", 680, 260)
                    val logoRequest = logoUrl?.let {
                        ImageRequest.Builder(LocalContext.current).data(it).httpHeaders(
                            NetworkHeaders.Builder()
                                .set("Authorization", JellyfinApi.authorizationHeader(session.token))
                                .build()
                        ).crossfade(true).build()
                    }
                    if (logoRequest != null) {
                        AsyncImage(
                            model = logoRequest,
                            contentDescription = stringResource(R.string.logo_description, item.name),
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(260.dp, 72.dp)
                                .semantics { heading() },
                        )
                    } else {
                        Text(
                            item.name,
                            style = MaterialTheme.typography.headlineLarge,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() })
                    }
                    Text(
                        itemSubtitle(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = .65f)
                    )
                }
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp), horizontalArrangement = Arrangement.Center
    ) {
        repeat(items.size) { index ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (index == pagerState.currentPage) 18.dp else 6.dp, 4.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary else Color.White.copy(
                            alpha = .25f
                        )
                    )
            )
        }
    }
}

internal const val FEATURE_BAR_ASPECT_RATIO = 16f / 9f

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SearchScreen(
    repository: JellyfinRepository,
    session: AuthSession,
    padding: PaddingValues,
    onItemClick: (MediaItem) -> Unit
) {
    val vm: SearchViewModel = viewModel(
        key = "search-${session.userId}",
        factory = SearchViewModel.Factory(repository, session)
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    Scaffold(
        modifier = Modifier.padding(padding),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::updateQuery,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) IconButton(onClick = {
                        vm.updateQuery(
                            ""
                        )
                    }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                },
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            when {
                state.loading -> CenterLoading(PaddingValues())
                state.error -> ErrorState(PaddingValues(), R.string.search_load_failed) {}
                state.query.trim().length < 2 -> Unit

                state.results.isEmpty() -> Text(
                    stringResource(R.string.no_search_results),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(state.results, key = { it.id }) { item ->
                        Box(
                            Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.TopCenter
                        ) { MediaCardForSearch(item, session, onItemClick) }
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
    onItemClick: (MediaItem) -> Unit
) {
    com.zenstream.zenstreammobile.ui.components.MediaCard(
        item,
        session,
        wide = false,
        onClick = onItemClick
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun LibraryScreen(
    repository: JellyfinRepository,
    session: AuthSession,
    padding: PaddingValues,
    onItemClick: (MediaItem) -> Unit
) {
    val vm: LibraryViewModel = viewModel(
        key = "library-${session.userId}",
        factory = LibraryViewModel.Factory(repository, session)
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    Scaffold(
        modifier = Modifier.padding(padding),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            if (state.libraries.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        state.libraries,
                        key = { it.id }) { library ->
                        FilterChip(
                            selected = state.selected?.id == library.id,
                            onClick = { vm.select(library) },
                            label = { Text(library.name) })
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
            when {
                state.loading -> CenterLoading(PaddingValues())
                state.error -> ErrorState(
                    PaddingValues(),
                    R.string.library_load_page_failed,
                    vm::loadLibraries
                )

                state.data?.rows?.isEmpty() != false -> Text(
                    stringResource(R.string.empty_library),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp)
                )

                else -> LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
                    items(
                        state.data?.rows.orEmpty(),
                        key = { "${it.title}:${it.libraryName}" }) {
                        MediaRowView(
                            it,
                            session,
                            onItemClick = onItemClick
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun PlaybackPlaceholderScreen(itemName: String, onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(
                    itemName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
    }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.media_playback_failed),
                style = MaterialTheme.typography.titleLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun CenterLoading(padding: PaddingValues) {
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding),
        contentAlignment = Alignment.Center
    ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun ErrorState(padding: PaddingValues, message: Int, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            stringResource(message),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) {
            Icon(Icons.Default.Refresh, contentDescription = null); Spacer(
            Modifier.width(6.dp)
        ); Text(stringResource(R.string.retry))
        }
    }
}

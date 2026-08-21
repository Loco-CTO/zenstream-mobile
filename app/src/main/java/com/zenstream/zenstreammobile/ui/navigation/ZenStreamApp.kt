package com.zenstream.zenstreammobile.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.AppUpdate
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.SyncplayManager
import com.zenstream.zenstreammobile.launchPlayback
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.PlaybackTrackSelection
import com.zenstream.zenstreammobile.model.SyncplayGroup
import com.zenstream.zenstreammobile.model.mediaItemId
import com.zenstream.zenstreammobile.ui.AppUiState
import com.zenstream.zenstreammobile.ui.AppViewModel
import com.zenstream.zenstreammobile.ui.NotificationsViewModel
import com.zenstream.zenstreammobile.ui.components.SyncplayToastNotifications
import com.zenstream.zenstreammobile.ui.components.ToastHost
import com.zenstream.zenstreammobile.ui.components.UserAvatar
import com.zenstream.zenstreammobile.ui.components.rememberToastHostState
import com.zenstream.zenstreammobile.ui.screens.DetailScreen
import com.zenstream.zenstreammobile.ui.screens.FavoritesScreen
import com.zenstream.zenstreammobile.ui.screens.HomeScreen
import com.zenstream.zenstreammobile.ui.screens.LibraryScreen
import com.zenstream.zenstreammobile.ui.screens.LoginScreen
import com.zenstream.zenstreammobile.ui.screens.MyPageScreen
import com.zenstream.zenstreammobile.ui.screens.NotificationsScreen
import com.zenstream.zenstreammobile.ui.screens.SearchOverlayScreen
import com.zenstream.zenstreammobile.ui.screens.ServerSetupScreen
import com.zenstream.zenstreammobile.ui.screens.SyncplayGroupMenu
import kotlinx.coroutines.launch

private const val HOME = "home"
private const val SEARCH = "search"
private const val LIBRARY = "library"
private const val FAVORITES = "favorites"
private const val MYPAGE = "my-page"
private const val NOTIFICATIONS = "notifications"
private const val DETAIL = "detail/{itemId}"

internal fun shouldShowMainSearchAction(route: String): Boolean =
    route == HOME || route == FAVORITES || route == LIBRARY

@Composable
fun ZenStreamApp(
    appState: AppUiState,
    repository: CatalogRepository,
    appViewModel: AppViewModel,
    onPickAvatar: () -> Unit = {},
    avatarPickerResult: Uri? = null,
    onAvatarPickerResultConsumed: () -> Unit = {},
) {
    val context = LocalContext.current
    when {
        appState.loading -> LoadingScreen()
        appState.showSetup ->
            ServerSetupScreen(
                initialServerUrl = appState.orchestratorUrl,
                onConfigured = appViewModel::configureServer,
            )

        appState.showLogin -> LoginScreen(repository, appViewModel::changeServer)
        appState.session != null ->
            MainScaffold(
                repository = repository,
                session = appState.session,
                onLogout = appViewModel::logout,
                onPasswordChanged = appViewModel::passwordChanged,
                onPickAvatar = onPickAvatar,
                avatarPickerResult = avatarPickerResult,
                onAvatarPickerResultConsumed = onAvatarPickerResultConsumed,
            )
        else -> LoadingScreen()
    }

    appState.availableUpdate?.let { update ->
        UpdateAvailableDialog(
            update = update,
            onDismiss = appViewModel::dismissAvailableUpdate,
            onOpenRelease = {
                appViewModel.dismissAvailableUpdate()
                openReleasePage(context, update.releaseUrl)
            },
        )
    }
}

internal fun openReleasePage(context: Context, url: String): Boolean {
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    return runCatching { context.startActivity(intent) }.isSuccess
}

@Composable
internal fun UpdateAvailableDialog(
    update: AppUpdate,
    onDismiss: () -> Unit,
    onOpenRelease: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_available_title)) },
        text = { Text(stringResource(R.string.update_available_message, update.version)) },
        confirmButton = {
            TextButton(onClick = onOpenRelease) {
                Text(stringResource(R.string.update_open_release))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.update_not_now))
            }
        },
    )
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    repository: CatalogRepository,
    session: AuthSession,
    onLogout: () -> Unit,
    onPasswordChanged: () -> Unit,
    onPickAvatar: () -> Unit,
    avatarPickerResult: Uri?,
    onAvatarPickerResultConsumed: () -> Unit,
) {
    val syncplay = remember(session.token) { repository.syncplayManager(session) }
    val syncplayState by syncplay.state.collectAsStateWithLifecycle()
    val notificationsViewModel: NotificationsViewModel =
        viewModel(
            key = "notifications-${session.userId}-${session.token}",
            factory = NotificationsViewModel.Factory(repository, session),
        )
    val notificationsState by notificationsViewModel.uiState.collectAsStateWithLifecycle()
    val toast = rememberToastHostState()
    val scope = rememberCoroutineScope()
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = routeName(backStackEntry?.destination?.route) ?: HOME
    val mainRoute =
        if (currentRoute == SEARCH) {
            routeName(navController.previousBackStackEntry?.destination?.route) ?: HOME
        } else {
            currentRoute
        }
    val searchOverlayOpen = currentRoute == SEARCH
    val density = LocalDensity.current
    val context = LocalContext.current
    var followedGeneration by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(
        syncplayState.active?.id,
        syncplayState.active?.itemId,
        syncplayState.active?.mediaGeneration,
        syncplayState.active?.revision,
        syncplayState.currentMember()?.watchingTogether,
        syncplayState.participantId,
    ) {
        val room = syncplayState.active
        val member = syncplayState.currentMember()
        val itemId = room?.mediaItemId()
        val key = room?.let { "${it.id}:${it.mediaGeneration}:${itemId}" }
        // A media command begins paused while the Syncplay readiness barrier waits
        // for every opted-in participant.  Following only `playing` rooms leaves
        // Android outside the player, so it can never report itself ready.
        if (itemId != null && member?.watchingTogether == true && key != followedGeneration) {
            followedGeneration = key
            launchPlayback(context, itemId, "")
        } else if (itemId == null || member?.watchingTogether != true) {
            followedGeneration = null
        }
    }
    val bottomBarVisibility =
        remember(density) {
            ScrollVisibilityController(
                hideDistance = with(density) { HIDE_DISTANCE_DP.dp.toPx() },
                revealDistance = with(density) { REVEAL_DISTANCE_DP.dp.toPx() },
            )
        }
    val topBarVisibility =
        remember(density) {
            ScrollVisibilityController(
                hideDistance = with(density) { HIDE_DISTANCE_DP.dp.toPx() },
                revealDistance = with(density) { REVEAL_DISTANCE_DP.dp.toPx() },
            )
        }
    var bottomBarVisible by remember { mutableStateOf(true) }
    var topBarVisible by remember { mutableStateOf(true) }
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                bottomBarVisible =
                    bottomBarVisibility.onNestedScroll(
                        consumedY = consumed.y,
                        availableY = available.y,
                    )
                topBarVisible =
                    topBarVisibility.onNestedScroll(
                        consumedY = consumed.y,
                        availableY = available.y,
                    )
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(mainRoute) {
        bottomBarVisible = bottomBarVisibility.resetForRoute()
        topBarVisible = topBarVisibility.resetForRoute()
        if (mainRoute == NOTIFICATIONS) notificationsViewModel.refresh()
    }
    LaunchedEffect(session.token) { notificationsViewModel.refresh() }

    val detailRoute = mainRoute == DETAIL.substringBefore("/")
    val topBarHidden = detailRoute || mainRoute == MYPAGE || mainRoute == NOTIFICATIONS

    androidx.compose.material3.Scaffold(
        topBar = {
            if (!topBarHidden) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                ) {
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
                        MainTopBar(
                            syncplay = syncplay,
                            session = session,
                            showSearchAction =
                                !searchOverlayOpen && shouldShowMainSearchAction(mainRoute),
                            onSearch = { navigateToSearch(navController) },
                            unreadCount = notificationsState.unreadCount,
                            onNotifications = {
                                navController.navigate(NOTIFICATIONS) { launchSingleTop = true }
                            },
                            onReturnToView = { group ->
                                group.mediaItemId()?.let { launchPlayback(context, it, "") }
                            },
                        )
                    }
                }
            }
        },
        bottomBar = {
            if (!detailRoute && mainRoute != NOTIFICATIONS) {
                // Keep the system navigation-control surface mounted while the
                // menu items animate. This prevents content from showing through
                // the Android control strip during the transition.
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .navigationBarsPadding()
                ) {
                    AnimatedVisibility(
                        visible = bottomBarVisible,
                        enter =
                            expandVertically(expandFrom = Alignment.Bottom) +
                                slideInVertically(initialOffsetY = { it }) +
                                fadeIn(),
                        exit =
                            shrinkVertically(shrinkTowards = Alignment.Bottom) +
                                slideOutVertically(targetOffsetY = { it }) +
                                fadeOut(),
                    ) {
                        MainNavigationBar(
                            currentRoute = mainRoute,
                            session = session,
                            onDestinationClick = { route ->
                                navigateToMainDestination(navController, route)
                            },
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController,
                startDestination = HOME,
                modifier = Modifier.fillMaxSize().nestedScroll(scrollConnection),
            ) {
                composable(HOME) {
                    HomeScreen(
                        repository,
                        session,
                        padding,
                        onItemClick = { item -> navigateToDetail(navController, item.id) },
                    )
                }
                dialog(
                    SEARCH,
                    dialogProperties =
                        DialogProperties(
                            usePlatformDefaultWidth = false,
                            decorFitsSystemWindows = false,
                            dismissOnBackPress = true,
                            dismissOnClickOutside = false,
                        ),
                ) {
                    SearchOverlayScreen(
                        repository = repository,
                        session = session,
                        onDismiss = { navController.popBackStack() },
                        onItemClick = { item ->
                            navigateToDetail(navController, item.id)
                        },
                    )
                }
                composable(LIBRARY) {
                    LibraryScreen(
                        repository,
                        session,
                        padding,
                    ) { item ->
                        navigateToDetail(navController, item.id)
                    }
                }
                composable(FAVORITES) {
                    FavoritesScreen(
                        repository,
                        session,
                        padding,
                    ) { item ->
                        navigateToDetail(navController, item.id)
                    }
                }
                composable(MYPAGE) {
                    MyPageScreen(
                        repository = repository,
                        session = session,
                        onLogout = onLogout,
                        onPasswordChanged = onPasswordChanged,
                        outerPadding = padding,
                        onPickAvatar = onPickAvatar,
                        avatarPickerResult = avatarPickerResult,
                        onAvatarPickerResultConsumed = onAvatarPickerResultConsumed,
                        onOpenItem = { itemId -> navigateToDetail(navController, itemId) },
                        onOpenNotifications = {
                            navController.navigate(NOTIFICATIONS) { launchSingleTop = true }
                        },
                    )
                }
                composable(NOTIFICATIONS) {
                    NotificationsScreen(
                        repository = repository,
                        session = session,
                        onBack = { navController.popBackStack() },
                        onOpenItem = { itemId -> navigateToDetail(navController, itemId) },
                    )
                }
                composable(
                    DETAIL,
                    arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
                ) { entry ->
                    val itemId = Uri.decode(entry.arguments?.getString("itemId").orEmpty())
                    DetailScreen(
                        repository = repository,
                        session = session,
                        itemId = itemId,
                        outerPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        onBack = { navController.popBackStack() },
                        onOpenItem = { item -> navigateToDetail(navController, item.id) },
                        onPlay = { item, tracks ->
                            scope.launch {
                                val active = syncplay.state.value.active
                                if (
                                    active == null ||
                                        syncplay.state.value.canControl(session.userId)
                                ) {
                                    if (active != null) {
                                        syncplay.command("media", 0.0, true, item.id)
                                        syncplay.state.value.active?.let { updated ->
                                            followedGeneration =
                                                updated.mediaItemId()?.let { itemId ->
                                                    "${updated.id}:${updated.mediaGeneration}:$itemId"
                                                }
                                        }
                                    }
                                    navigateToPlayback(context, item.id, item.name, tracks)
                                }
                            }
                        },
                    )
                }
            }
            SyncplayToastNotifications(
                manager = syncplay,
                repository = repository,
                session = session,
                toast = toast,
            )
            ToastHost(state = toast)
        }
    }
}

private fun navigateToDetail(navController: androidx.navigation.NavHostController, itemId: String) {
    navController.navigate("detail/${Uri.encode(itemId)}") {
        launchSingleTop = true
    }
}

internal fun navigateToMainDestination(
    navController: androidx.navigation.NavHostController,
    route: String,
) {
    // Search is a transient route pushed above the main destinations. Remove it before restoring
    // the selected tab so a saved Search entry cannot remain visible over the requested page.
    if (routeName(navController.currentDestination?.route) == SEARCH) {
        navController.popBackStack()
    }
    if (navController.currentDestination?.route == route) return

    navController.navigate(route) {
        popUpTo(HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun navigateToSearch(navController: androidx.navigation.NavHostController) {
    navController.navigate(SEARCH) {
        launchSingleTop = true
        restoreState = true
    }
}

private fun routeName(route: String?): String? = route?.substringBefore("/")?.substringBefore("?")

private fun navigateToPlayback(
    context: Context,
    itemId: String,
    itemName: String,
    tracks: PlaybackTrackSelection? = null,
) {
    launchPlayback(context, itemId, itemName, tracks)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainTopBar(
    syncplay: SyncplayManager? = null,
    session: AuthSession? = null,
    onReturnToView: (SyncplayGroup) -> Unit = {},
    showSearchAction: Boolean = false,
    onSearch: () -> Unit = {},
    unreadCount: Int = 0,
    onNotifications: () -> Unit = {},
) {
    TopAppBar(
        title = {
            Image(
                painter = painterResource(com.zenstream.zenstreammobile.R.mipmap.zenstream_logo),
                contentDescription =
                    stringResource(com.zenstream.zenstreammobile.R.string.app_logo_description),
                modifier = Modifier.size(32.dp),
            )
        },
        actions = {
            Box {
                IconButton(onClick = onNotifications) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_bell),
                        contentDescription = stringResource(R.string.notifications),
                    )
                }
                if (unreadCount > 0) {
                    Box(
                        modifier =
                            Modifier.align(Alignment.TopEnd)
                                .padding(top = 4.dp, end = 4.dp)
                                .size(16.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (unreadCount > 9) "9+" else unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
            if (showSearchAction) {
                IconButton(onClick = onSearch) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_search),
                        contentDescription = stringResource(R.string.search),
                    )
                }
            }
            if (syncplay != null && session != null) {
                SyncplayGroupMenu(
                    manager = syncplay,
                    session = session,
                    onReturnToView = onReturnToView,
                )
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background
            ),
    )
}

internal data class NavigationDestination(
    val route: String,
    val label: Int,
    @androidx.annotation.DrawableRes val icon: Int? = null,
)

internal fun mainNavigationDestinations(): List<NavigationDestination> =
    listOf(
        NavigationDestination(
            HOME,
            com.zenstream.zenstreammobile.R.string.home,
            LucideR.drawable.lucide_ic_house,
        ),
        NavigationDestination(
            FAVORITES,
            com.zenstream.zenstreammobile.R.string.favorites,
            LucideR.drawable.lucide_ic_heart,
        ),
        NavigationDestination(
            LIBRARY,
            com.zenstream.zenstreammobile.R.string.library,
            LucideR.drawable.lucide_ic_library_big,
        ),
        NavigationDestination(
            MYPAGE,
            com.zenstream.zenstreammobile.R.string.my_page,
        ),
    )

@Composable
internal fun MainNavigationBar(
    currentRoute: String,
    session: AuthSession,
    onDestinationClick: (String) -> Unit,
) {
    androidx.compose.material3.NavigationBar(
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 3.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        mainNavigationDestinations().forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onDestinationClick(destination.route) },
                colors =
                    androidx.compose.material3.NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        indicatorColor = Color.Transparent,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                icon = {
                    if (destination.route == MYPAGE) {
                        Box(Modifier.size(24.dp)) {
                            UserAvatar(
                                session = session,
                                userId = session.userId,
                                username = session.username,
                                modifier = Modifier.size(24.dp),
                                contentDescription = stringResource(R.string.my_page),
                            )
                            if (currentRoute == destination.route) {
                                Box(
                                    Modifier.fillMaxSize()
                                        .border(
                                            width = 1.5.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = CircleShape,
                                        )
                                )
                            }
                        }
                    } else {
                        destination.icon?.let { icon ->
                            Icon(
                                painter = painterResource(icon),
                                contentDescription = null,
                            )
                        }
                    }
                },
                label = {
                    androidx.compose.material3.Text(stringResource(destination.label))
                },
            )
        }
    }
}

internal class ScrollVisibilityController(
    private val hideDistance: Float,
    private val revealDistance: Float,
) {
    private enum class ScrollDirection {
        HIDE,
        REVEAL,
    }

    private var direction: ScrollDirection? = null
    private var accumulatedDistance = 0f
    private var isVisible = true

    /**
     * Applies only movement consumed by a scrollable child. Unconsumed upward drags are common on
     * empty/short screens and must not hide the chrome.
     */
    fun onNestedScroll(consumedY: Float, availableY: Float): Boolean =
        onScroll(
            deltaY = consumedY,
            atTop = availableY > 0f && consumedY == 0f,
        )

    fun onScroll(deltaY: Float, atTop: Boolean = false): Boolean {
        if (atTop) {
            return reset(visible = true)
        }

        val nextDirection =
            when {
                deltaY < 0f -> ScrollDirection.HIDE
                deltaY > 0f -> ScrollDirection.REVEAL
                else -> return isVisible
            }

        if (direction != nextDirection) {
            direction = nextDirection
            accumulatedDistance = 0f
        }
        accumulatedDistance += kotlin.math.abs(deltaY)

        when (nextDirection) {
            ScrollDirection.HIDE ->
                if (accumulatedDistance >= hideDistance) {
                    isVisible = false
                }

            ScrollDirection.REVEAL ->
                if (accumulatedDistance >= revealDistance) {
                    isVisible = true
                }
        }
        return isVisible
    }

    fun resetForRoute(): Boolean = reset(visible = true)

    private fun reset(visible: Boolean): Boolean {
        direction = null
        accumulatedDistance = 0f
        isVisible = visible
        return isVisible
    }
}

private const val HIDE_DISTANCE_DP = 56f
private const val REVEAL_DISTANCE_DP = 64f

package com.zenstream.zenstreammobile.ui.navigation

import android.content.Context
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.composables.icons.lucide.R as LucideR
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zenstream.zenstreammobile.data.JellyfinRepository
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.R
import androidx.compose.ui.res.painterResource
import com.zenstream.zenstreammobile.ui.AppUiState
import com.zenstream.zenstreammobile.ui.AppViewModel
import com.zenstream.zenstreammobile.launchPlayback
import com.zenstream.zenstreammobile.ui.screens.DetailScreen
import com.zenstream.zenstreammobile.ui.screens.HomeScreen
import com.zenstream.zenstreammobile.ui.screens.LibraryScreen
import com.zenstream.zenstreammobile.ui.screens.LoginScreen
import com.zenstream.zenstreammobile.ui.screens.SearchScreen
import com.zenstream.zenstreammobile.ui.screens.SettingsScreen
import com.zenstream.zenstreammobile.ui.screens.ServerSetupScreen

private const val HOME = "home"
private const val SEARCH = "search"
private const val LIBRARY = "library"
private const val DETAIL = "detail/{itemId}"
private const val SETTINGS = "settings"

@Composable
fun ZenStreamApp(appState: AppUiState, repository: JellyfinRepository, appViewModel: AppViewModel) {
    when {
        appState.loading -> LoadingScreen()
        appState.showSetup -> ServerSetupScreen(
            initialServerUrl = appState.orchestratorUrl,
            onConfigured = appViewModel::configureServer,
        )
        appState.showLogin -> LoginScreen(repository, appViewModel::changeServer)
        appState.session != null -> MainScaffold(repository, appState.session, appViewModel::logout)
        else -> LoadingScreen()
    }
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
    repository: JellyfinRepository,
    session: AuthSession,
    onLogout: () -> Unit
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route?.substringBefore("/") ?: HOME
    val destinations = remember {
        listOf(
            NavigationDestination(
                HOME,
                com.zenstream.zenstreammobile.R.string.home,
                LucideR.drawable.lucide_ic_house
            ),
            NavigationDestination(
                SEARCH,
                com.zenstream.zenstreammobile.R.string.search,
                LucideR.drawable.lucide_ic_search
            ),
            NavigationDestination(
                LIBRARY,
                com.zenstream.zenstreammobile.R.string.library,
                LucideR.drawable.lucide_ic_library
            )
        )
    }
    val density = LocalDensity.current
    val context = LocalContext.current
    val bottomBarVisibility = remember(density) {
        ScrollVisibilityController(
            hideDistance = with(density) { HIDE_DISTANCE_DP.dp.toPx() },
            revealDistance = with(density) { REVEAL_DISTANCE_DP.dp.toPx() }
        )
    }
    var bottomBarVisible by remember { mutableStateOf(true) }
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                val deltaY = consumed.y + available.y
                bottomBarVisible = bottomBarVisibility.onScroll(
                    deltaY = deltaY,
                    atTop = available.y > 0f && consumed.y == 0f
                )
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(currentRoute) {
        bottomBarVisible = bottomBarVisibility.resetForRoute()
    }

    val chromeHidden = currentRoute == DETAIL.substringBefore("/") ||
            currentRoute == SETTINGS

    androidx.compose.material3.Scaffold(
        topBar = {
            if (!chromeHidden) {
                MainTopBar(onSettings = { navController.navigate(SETTINGS) { launchSingleTop = true } })
            }
        },
        bottomBar = {
            if (!chromeHidden) {
                // Keep the system navigation-control surface mounted while the
                // menu items animate. This prevents content from showing through
                // the Android control strip during the transition.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .navigationBarsPadding()
                ) {
                    AnimatedVisibility(
                        visible = bottomBarVisible,
                        enter = expandVertically(expandFrom = Alignment.Bottom) +
                                slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = shrinkVertically(shrinkTowards = Alignment.Bottom) +
                                slideOutVertically(targetOffsetY = { it }) + fadeOut()
                    ) {
                        androidx.compose.material3.NavigationBar(
                            containerColor = Color.Transparent,
                            windowInsets = WindowInsets(0, 0, 0, 0)
                        ) {
                            destinations.forEach { destination ->
                                NavigationBarItem(
                                    selected = currentRoute == destination.route,
                                    onClick = {
                                        navController.navigate(destination.route) {
                                            popUpTo(HOME) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            painter = painterResource(destination.icon),
                                            contentDescription = null
                                        )
                                    },
                                    label = {
                                        androidx.compose.material3.Text(
                                            androidx.compose.ui.res.stringResource(
                                                destination.label
                                            )
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        NavHost(
            navController,
            startDestination = HOME,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollConnection)
        ) {
            composable(HOME) {
                HomeScreen(
                    repository,
                    session,
                    padding,
                    onPlay = { item -> navigateToDetail(navController, item.id) })
            }
            composable(SEARCH) {
                SearchScreen(
                    repository,
                    session,
                    padding
                ) { item -> navigateToDetail(navController, item.id) }
            }
            composable(LIBRARY) {
                LibraryScreen(
                    repository,
                    session,
                    padding
                ) { item -> navigateToDetail(navController, item.id) }
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
                    onPlay = { item -> navigateToPlayback(context, item.id, item.name) },
                )
            }
            composable(SETTINGS) {
                SettingsScreen(
                    repository = repository,
                    onBack = { navController.popBackStack() },
                    onLogout = onLogout,
                )
            }
        }
    }
}

private fun navigateToDetail(navController: androidx.navigation.NavHostController, itemId: String) {
    navController.navigate("detail/${Uri.encode(itemId)}")
}

private fun navigateToPlayback(
    context: Context,
    itemId: String,
    itemName: String,
) {
    launchPlayback(context, itemId, itemName)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainTopBar(onSettings: () -> Unit = {}) {
    TopAppBar(
        title = {
            Image(
                painter = painterResource(com.zenstream.zenstreammobile.R.mipmap.zenstream_logo),
                contentDescription = stringResource(
                    com.zenstream.zenstreammobile.R.string.app_logo_description
                ),
                modifier = Modifier.size(32.dp),
            )
        },
        actions = {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.IconButton(onClick = onSettings) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_settings),
                        contentDescription = stringResource(com.zenstream.zenstreammobile.R.string.settings_description),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

private data class NavigationDestination(
    val route: String,
    val label: Int,
    @androidx.annotation.DrawableRes val icon: Int
)

internal class ScrollVisibilityController(
    private val hideDistance: Float,
    private val revealDistance: Float
) {
    private enum class ScrollDirection {
        HIDE,
        REVEAL
    }

    private var direction: ScrollDirection? = null
    private var accumulatedDistance = 0f
    private var isVisible = true

    fun onScroll(deltaY: Float, atTop: Boolean = false): Boolean {
        if (atTop) {
            return reset(visible = true)
        }

        val nextDirection = when {
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
            ScrollDirection.HIDE -> if (accumulatedDistance >= hideDistance) {
                isVisible = false
            }

            ScrollDirection.REVEAL -> if (accumulatedDistance >= revealDistance) {
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

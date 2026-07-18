package com.zenstream.zenstreammobile.ui.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zenstream.zenstreammobile.data.JellyfinRepository
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.ui.AppUiState
import com.zenstream.zenstreammobile.ui.AppViewModel
import com.zenstream.zenstreammobile.ui.screens.HomeScreen
import com.zenstream.zenstreammobile.ui.screens.LibraryScreen
import com.zenstream.zenstreammobile.ui.screens.LoginScreen
import com.zenstream.zenstreammobile.ui.screens.PlaybackPlaceholderScreen
import com.zenstream.zenstreammobile.ui.screens.SearchScreen
import com.zenstream.zenstreammobile.ui.screens.ServerSetupScreen

private const val HOME = "home"
private const val SEARCH = "search"
private const val LIBRARY = "library"
private const val PLAYBACK = "playback/{itemId}/{itemName}"

@Composable
fun ZenStreamApp(appState: AppUiState, repository: JellyfinRepository, appViewModel: AppViewModel) {
    when {
        appState.loading -> LoadingScreen()
        appState.showSetup -> ServerSetupScreen { appViewModel.configureServer(it) }
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
                Icons.Default.Home
            ),
            NavigationDestination(
                SEARCH,
                com.zenstream.zenstreammobile.R.string.search,
                Icons.Default.Search
            ),
            NavigationDestination(
                LIBRARY,
                com.zenstream.zenstreammobile.R.string.library,
                Icons.Default.VideoLibrary
            )
        )
    }
    val density = LocalDensity.current
    val bottomBarVisibility = remember(density) {
        BottomBarVisibilityController(
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

    androidx.compose.material3.Scaffold(
        bottomBar = {
            if (currentRoute != PLAYBACK.substringBefore("/")) {
                AnimatedVisibility(
                    visible = bottomBarVisible,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        androidx.compose.material3.NavigationBar(containerColor = androidx.compose.ui.graphics.Color.Transparent) {
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
                                        androidx.compose.material3.Icon(
                                            destination.icon,
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
                    onPlay = { item ->
                        navController.navigate(
                            "playback/${Uri.encode(item.id)}/${
                                Uri.encode(item.name)
                            }"
                        )
                    })
            }
            composable(SEARCH) {
                SearchScreen(
                    repository,
                    session,
                    padding
                ) { item ->
                    navController.navigate(
                        "playback/${Uri.encode(item.id)}/${
                            Uri.encode(
                                item.name
                            )
                        }"
                    )
                }
            }
            composable(LIBRARY) {
                LibraryScreen(
                    repository,
                    session,
                    padding
                ) { item ->
                    navController.navigate(
                        "playback/${Uri.encode(item.id)}/${
                            Uri.encode(
                                item.name
                            )
                        }"
                    )
                }
            }
            composable(
                PLAYBACK,
                arguments = listOf(
                    navArgument("itemId") { type = NavType.StringType },
                    navArgument("itemName") { type = NavType.StringType })
            ) { entry ->
                PlaybackPlaceholderScreen(
                    Uri.decode(
                        entry.arguments?.getString("itemName").orEmpty()
                    )
                ) { navController.popBackStack() }
            }
        }
    }
}

private data class NavigationDestination(
    val route: String,
    val label: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

internal class BottomBarVisibilityController(
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

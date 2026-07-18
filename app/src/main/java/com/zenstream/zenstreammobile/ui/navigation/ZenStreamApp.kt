package com.zenstream.zenstreammobile.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    androidx.compose.material3.Scaffold(
        bottomBar = {
            if (currentRoute != PLAYBACK.substringBefore("/")) {
                androidx.compose.material3.NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
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
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        NavHost(navController, startDestination = HOME, modifier = Modifier.fillMaxSize()) {
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

package com.zenstream.zenstreammobile

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenstream.zenstreammobile.data.JellyfinApi
import com.zenstream.zenstreammobile.data.JellyfinRepository
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.ui.AppViewModel
import com.zenstream.zenstreammobile.ui.locale.ZenStreamLocale
import com.zenstream.zenstreammobile.ui.navigation.ZenStreamApp
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme

class MainActivity : ComponentActivity() {
    private val repository by lazy {
        JellyfinRepository(JellyfinApi(), SessionStore(applicationContext))
    }
    private val appViewModel by viewModels<AppViewModel> { AppViewModel.Factory(repository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Let the Compose navigation surface provide the color behind the
            // three-button controls instead of Android adding a lighter scrim.
            window.isNavigationBarContrastEnforced = false
        }
        setContent {
            ZenStreamTheme {
                val appState by appViewModel.uiState.collectAsStateWithLifecycle()
                ZenStreamLocale(appState.locale) {
                    ZenStreamApp(appState, repository, appViewModel)
                }
            }
        }
    }
}

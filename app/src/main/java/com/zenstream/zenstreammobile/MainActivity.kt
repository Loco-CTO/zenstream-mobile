package com.zenstream.zenstreammobile

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zenstream.zenstreammobile.data.ACCESS_LOCAL_NETWORK_PERMISSION
import com.zenstream.zenstreammobile.data.AuthLifecycleLog
import com.zenstream.zenstreammobile.data.CatalogApi
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.ui.AppViewModel
import com.zenstream.zenstreammobile.ui.locale.ZenStreamLocale
import com.zenstream.zenstreammobile.ui.navigation.ZenStreamApp
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme

class MainActivity : ComponentActivity() {
    private var avatarPickerResult by mutableStateOf<Uri?>(null)
    private var localNetworkPermissionGranted by mutableStateOf(false)
    private val pendingLocalNetworkPermissionResults = mutableListOf<(Boolean) -> Unit>()
    private val avatarPicker =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            avatarPickerResult = uri
        }
    private val localNetworkPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            localNetworkPermissionGranted = hasLocalNetworkPermission()
            val callbacks = pendingLocalNetworkPermissionResults.toList()
            pendingLocalNetworkPermissionResults.clear()
            callbacks.forEach { callback -> callback(granted && localNetworkPermissionGranted) }
        }

    private val repository by lazy {
        CatalogRepository(CatalogApi(), SessionStore(applicationContext))
    }
    private val appViewModel by viewModels<AppViewModel> { AppViewModel.Factory(repository) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthLifecycleLog.processCreated(android.os.Process.myPid())
        appViewModel.handleNativeAppLink(intent?.data?.toString())
        localNetworkPermissionGranted = hasLocalNetworkPermission()
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
                    ZenStreamApp(
                        appState = appState,
                        repository = repository,
                        appViewModel = appViewModel,
                        localNetworkPermissionGranted = localNetworkPermissionGranted,
                        onRequestLocalNetworkPermission = ::requestLocalNetworkPermission,
                        onOpenLocalNetworkSettings = ::openLocalNetworkSettings,
                        onPickAvatar = {
                            avatarPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        avatarPickerResult = avatarPickerResult,
                        onAvatarPickerResultConsumed = { avatarPickerResult = null },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AuthLifecycleLog.appResumed()
    }

    override fun onResume() {
        super.onResume()
        localNetworkPermissionGranted = hasLocalNetworkPermission()
    }

    override fun onStop() {
        AuthLifecycleLog.appBackgrounded()
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        appViewModel.handleNativeAppLink(intent.data?.toString())
    }

    private fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < 37 ||
            checkSelfPermission(ACCESS_LOCAL_NETWORK_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestLocalNetworkPermission(onResult: (Boolean) -> Unit) {
        if (hasLocalNetworkPermission()) {
            localNetworkPermissionGranted = hasLocalNetworkPermission()
            onResult(true)
            return
        }
        if (pendingLocalNetworkPermissionResults.isNotEmpty()) {
            pendingLocalNetworkPermissionResults += onResult
            return
        }
        pendingLocalNetworkPermissionResults += onResult
        localNetworkPermissionLauncher.launch(ACCESS_LOCAL_NETWORK_PERMISSION)
    }

    private fun openLocalNetworkSettings() {
        val intent =
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
        startActivity(intent)
    }
}

package com.zenstream.zenstreammobile

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.data.ACCESS_LOCAL_NETWORK_PERMISSION
import com.zenstream.zenstreammobile.data.AuthLifecycleLog
import com.zenstream.zenstreammobile.data.CatalogApi
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.ui.AppViewModel
import com.zenstream.zenstreammobile.ui.locale.ZenStreamLocale
import com.zenstream.zenstreammobile.ui.navigation.ZenStreamApp
import com.zenstream.zenstreammobile.ui.player.VideoMiniPlayerHost
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme

class MainActivity : ComponentActivity() {
    private var miniSystemPip by mutableStateOf(false)
    private var miniPlaying = false
    private var miniAutomaticPip = false
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
        CatalogRepository(
            CatalogApi(),
            SessionStore(applicationContext),
            offline = com.zenstream.zenstreammobile.offline.OfflineRuntime.get(applicationContext),
        )
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
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier.fillMaxSize().graphicsLayer {
                                alpha = if (miniSystemPip) 0f else 1f
                            }
                        ) {
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
                        VideoMiniPlayerHost(
                            repository,
                            appState.session,
                            miniSystemPip,
                            sessionResolved =
                                appState.session != null ||
                                    appState.showLogin ||
                                    appState.showSetup,
                        ) { playing, enabled ->
                            miniPlaying = playing
                            miniAutomaticPip = enabled
                            updateMiniPipParams()
                        }
                    }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                miniPlaying &&
                miniAutomaticPip &&
                VideoPlaybackSessions.active?.presentation == VideoPresentation.MINI
        ) {
            miniSystemPip = true
            if (!enterPictureInPictureMode(miniPipParams())) miniSystemPip = false
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        miniSystemPip = isInPictureInPictureMode
    }

    private fun updateMiniPipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            setPictureInPictureParams(miniPipParams())
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun miniPipParams(): PictureInPictureParams {
        val actions =
            listOf(
                    Triple(
                        PlaybackPipActionReceiver.ACTION_PREVIOUS,
                        LucideR.drawable.lucide_ic_skip_back,
                        getString(R.string.player_previous),
                    ),
                    Triple(
                        PlaybackPipActionReceiver.ACTION_TOGGLE,
                        if (miniPlaying) LucideR.drawable.lucide_ic_pause
                        else LucideR.drawable.lucide_ic_play,
                        getString(if (miniPlaying) R.string.pause else R.string.play),
                    ),
                    Triple(
                        PlaybackPipActionReceiver.ACTION_NEXT,
                        LucideR.drawable.lucide_ic_skip_forward,
                        getString(R.string.player_next),
                    ),
                    Triple(
                        PlaybackPipActionReceiver.ACTION_CLOSE,
                        LucideR.drawable.lucide_ic_x,
                        getString(R.string.player_close),
                    ),
                )
                .map { (action, icon, label) ->
                    val intent =
                        Intent(this, PlaybackPipActionReceiver::class.java)
                            .setAction(action)
                            .putExtra(PlaybackPipActionReceiver.EXTRA_INSTANCE_ID, "in-app-mini")
                    RemoteAction(
                        Icon.createWithResource(this, icon),
                        label,
                        label,
                        PendingIntent.getBroadcast(
                            this,
                            action.hashCode(),
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                }
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)
            .build()
    }

    override fun onDestroy() {
        if (
            isFinishing &&
                !isChangingConfigurations &&
                VideoPlaybackSessions.active?.presentation == VideoPresentation.MINI
        )
            VideoPlaybackSessions.close()
        super.onDestroy()
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

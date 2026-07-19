package com.zenstream.zenstreammobile

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.zenstream.zenstreammobile.data.JellyfinApi
import com.zenstream.zenstreammobile.data.JellyfinRepository
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.ui.locale.ZenStreamLocale
import com.zenstream.zenstreammobile.ui.screens.PlaybackScreen
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object PlaybackActivityContract {
    const val EXTRA_ITEM_ID = "com.zenstream.zenstreammobile.extra.PLAYBACK_ITEM_ID"
    const val EXTRA_ITEM_NAME = "com.zenstream.zenstreammobile.extra.PLAYBACK_ITEM_NAME"
}

data class PlaybackLaunchArgs(val itemId: String, val itemName: String)

internal fun parsePlaybackLaunchArgs(itemId: String?, itemName: String?): PlaybackLaunchArgs? =
    itemId?.takeIf { it.isNotBlank() }?.let { PlaybackLaunchArgs(it, itemName.orEmpty()) }

fun playbackIntent(context: Context, itemId: String, itemName: String): Intent =
    Intent(context, PlaybackActivity::class.java).apply {
        putExtra(PlaybackActivityContract.EXTRA_ITEM_ID, itemId)
        putExtra(PlaybackActivityContract.EXTRA_ITEM_NAME, itemName)
    }

fun launchPlayback(context: Context, itemId: String, itemName: String) {
    val intent = playbackIntent(context, itemId, itemName)
    val activity = context.findActivity()
    if (activity != null) {
        activity.startActivity(intent)
    } else {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

class PlaybackActivity : ComponentActivity() {
    private var immersiveModeApplied = false

    private val repository by lazy {
        JellyfinRepository(JellyfinApi(), SessionStore(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        applyImmersiveMode()

        val args = parsePlaybackLaunchArgs(
            intent.getStringExtra(PlaybackActivityContract.EXTRA_ITEM_ID),
            intent.getStringExtra(PlaybackActivityContract.EXTRA_ITEM_NAME),
        ) ?: run {
            finish()
            return
        }

        lifecycleScope.launch {
            val session = repository.session.first()
            if (session == null || isFinishing) {
                finish()
                return@launch
            }
            val locale = repository.locale.first()
            setContent {
                ZenStreamTheme {
                    ZenStreamLocale(locale) {
                        PlaybackScreen(
                            repository = repository,
                            session = session,
                            itemId = args.itemId,
                            initialItemName = args.itemName,
                            onBack = ::finish,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isInPictureInPictureMode) applyImmersiveMode() else immersiveModeApplied = false
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        immersiveModeApplied = false
        if (!isInPictureInPictureMode && hasWindowFocus()) applyImmersiveMode()
    }

    override fun onPause() {
        if (isFinishing) restoreSystemBars()
        else immersiveModeApplied = false
        super.onPause()
    }

    private fun applyImmersiveMode() {
        if (isInPictureInPictureMode || immersiveModeApplied) return
        immersiveModeApplied = true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun restoreSystemBars() {
        immersiveModeApplied = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

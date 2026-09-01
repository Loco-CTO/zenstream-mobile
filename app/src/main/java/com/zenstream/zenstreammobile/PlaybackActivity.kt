package com.zenstream.zenstreammobile

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.zenstream.zenstreammobile.data.CatalogApi
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.data.DEFAULT_SESSION_DATA_STORE_NAME
import com.zenstream.zenstreammobile.data.INSTRUMENTATION_SESSION_DATA_STORE_NAME
import com.zenstream.zenstreammobile.data.SessionStore
import com.zenstream.zenstreammobile.model.PlaybackTrackSelection
import com.zenstream.zenstreammobile.ui.locale.ZenStreamLocale
import com.zenstream.zenstreammobile.ui.screens.PlaybackScreen
import com.zenstream.zenstreammobile.ui.theme.ZenStreamTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object PlaybackActivityContract {
    const val EXTRA_ITEM_ID = "com.zenstream.zenstreammobile.extra.PLAYBACK_ITEM_ID"
    const val EXTRA_ITEM_NAME = "com.zenstream.zenstreammobile.extra.PLAYBACK_ITEM_NAME"
    const val EXTRA_AUDIO_STREAM_ID = "com.zenstream.zenstreammobile.extra.AUDIO_STREAM_ID"
    const val EXTRA_SUBTITLE_STREAM_INDEX =
        "com.zenstream.zenstreammobile.extra.SUBTITLE_STREAM_INDEX"
    const val EXTRA_HAS_SUBTITLE_SELECTION =
        "com.zenstream.zenstreammobile.extra.HAS_SUBTITLE_SELECTION"
    internal const val EXTRA_SESSION_DATA_STORE =
        "com.zenstream.zenstreammobile.extra.SESSION_DATA_STORE"
}

data class PlaybackLaunchArgs(
    val itemId: String,
    val itemName: String,
    val audioStreamId: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val hasSubtitleSelection: Boolean = false,
)

internal fun parsePlaybackLaunchArgs(
    itemId: String?,
    itemName: String?,
    audioStreamId: Int? = null,
    subtitleStreamIndex: Int? = null,
    hasSubtitleSelection: Boolean = false,
): PlaybackLaunchArgs? =
    itemId
        ?.takeIf { it.isNotBlank() }
        ?.let {
            PlaybackLaunchArgs(
                it,
                itemName.orEmpty(),
                audioStreamId,
                subtitleStreamIndex,
                hasSubtitleSelection,
            )
        }

internal class PlaybackActivityLaunchGate {
    private enum class State {
        IDLE,
        LAUNCHING,
        ACTIVE,
    }

    private var state = State.IDLE

    @Synchronized
    fun beginLaunch(): Boolean =
        if (state == State.IDLE) {
            state = State.LAUNCHING
            true
        } else {
            false
        }

    @Synchronized
    fun claimActivity(): Boolean =
        when (state) {
            State.IDLE,
            State.LAUNCHING -> {
                state = State.ACTIVE
                true
            }

            State.ACTIVE -> false
        }

    @Synchronized
    fun cancelLaunch() {
        if (state == State.LAUNCHING) state = State.IDLE
    }

    @Synchronized
    fun releaseActivity() {
        if (state == State.ACTIVE) state = State.IDLE
    }
}

private val playbackActivityLaunchGate = PlaybackActivityLaunchGate()

internal fun shouldPausePlaybackForBackground(
    isFinishing: Boolean,
    isChangingConfigurations: Boolean,
    isInPictureInPictureMode: Boolean,
    enteringPictureInPicture: Boolean,
): Boolean =
    !isFinishing &&
        !isChangingConfigurations &&
        !isInPictureInPictureMode &&
        !enteringPictureInPicture

fun playbackIntent(
    context: Context,
    itemId: String,
    itemName: String,
    tracks: PlaybackTrackSelection? = null,
): Intent =
    Intent(context, PlaybackActivity::class.java).apply {
        putExtra(PlaybackActivityContract.EXTRA_ITEM_ID, itemId)
        putExtra(PlaybackActivityContract.EXTRA_ITEM_NAME, itemName)
        tracks?.let { selection ->
            selection.audioStreamId?.let {
                putExtra(PlaybackActivityContract.EXTRA_AUDIO_STREAM_ID, it)
            }
            selection.subtitleStreamIndex?.let {
                putExtra(PlaybackActivityContract.EXTRA_SUBTITLE_STREAM_INDEX, it)
            }
            putExtra(
                PlaybackActivityContract.EXTRA_HAS_SUBTITLE_SELECTION,
                selection.hasSubtitleSelection,
            )
        }
    }

fun launchPlayback(
    context: Context,
    itemId: String,
    itemName: String,
    tracks: PlaybackTrackSelection? = null,
) {
    if (!playbackActivityLaunchGate.beginLaunch()) return
    val intent = playbackIntent(context, itemId, itemName, tracks)
    val activity = context.findActivity()
    try {
        if (activity != null) {
            activity.startActivity(intent)
        } else {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } catch (error: RuntimeException) {
        playbackActivityLaunchGate.cancelLaunch()
        throw error
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

class PlaybackActivity : ComponentActivity() {
    private var immersiveModeApplied = false
    private var enteringPictureInPicture = false
    private var ownsPlaybackLaunch = false

    private val repository by lazy {
        val dataStoreName =
            if (
                BuildConfig.DEBUG &&
                    intent.getStringExtra(PlaybackActivityContract.EXTRA_SESSION_DATA_STORE) ==
                        INSTRUMENTATION_SESSION_DATA_STORE_NAME
            ) {
                INSTRUMENTATION_SESSION_DATA_STORE_NAME
            } else {
                DEFAULT_SESSION_DATA_STORE_NAME
            }
        CatalogRepository(
            CatalogApi(),
            SessionStore(applicationContext, dataStoreName = dataStoreName),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!playbackActivityLaunchGate.claimActivity()) {
            finish()
            return
        }
        ownsPlaybackLaunch = true
        enableEdgeToEdge()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        applyImmersiveMode()

        val args =
            parsePlaybackLaunchArgs(
                intent.getStringExtra(PlaybackActivityContract.EXTRA_ITEM_ID),
                intent.getStringExtra(PlaybackActivityContract.EXTRA_ITEM_NAME),
                intent
                    .takeIf { it.hasExtra(PlaybackActivityContract.EXTRA_AUDIO_STREAM_ID) }
                    ?.getIntExtra(PlaybackActivityContract.EXTRA_AUDIO_STREAM_ID, -1)
                    ?.takeIf { it >= 0 },
                intent
                    .takeIf { it.hasExtra(PlaybackActivityContract.EXTRA_SUBTITLE_STREAM_INDEX) }
                    ?.getIntExtra(PlaybackActivityContract.EXTRA_SUBTITLE_STREAM_INDEX, -1)
                    ?.takeIf { it >= 0 },
                intent.getBooleanExtra(
                    PlaybackActivityContract.EXTRA_HAS_SUBTITLE_SELECTION,
                    false,
                ),
            )
                ?: run {
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
                            syncplay = repository.syncplayManager(session),
                            itemId = args.itemId,
                            initialItemName = args.itemName,
                            initialAudioStreamId = args.audioStreamId,
                            initialSubtitleStreamIndex = args.subtitleStreamIndex,
                            hasInitialSubtitleSelection = args.hasSubtitleSelection,
                            enterPictureInPicture = ::enterPictureInPicture,
                            shouldPauseForBackground = ::shouldPauseForBackground,
                            onBack = ::finish,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enteringPictureInPicture = false
        applyImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isInPictureInPictureMode) applyImmersiveMode()
        else immersiveModeApplied = false
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        enteringPictureInPicture = false
        immersiveModeApplied = false
        if (!isInPictureInPictureMode && hasWindowFocus()) applyImmersiveMode()
    }

    override fun onPause() {
        if (isFinishing) restoreSystemBars() else immersiveModeApplied = false
        super.onPause()
    }

    override fun onDestroy() {
        if (ownsPlaybackLaunch) playbackActivityLaunchGate.releaseActivity()
        super.onDestroy()
    }

    private fun enterPictureInPicture(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || isInPictureInPictureMode) return false
        enteringPictureInPicture = true
        return enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
            .also { entered ->
                if (!entered) enteringPictureInPicture = false
            }
    }

    private fun shouldPauseForBackground(): Boolean =
        shouldPausePlaybackForBackground(
            isFinishing = isFinishing,
            isChangingConfigurations = isChangingConfigurations,
            isInPictureInPictureMode = isInPictureInPictureMode,
            enteringPictureInPicture = enteringPictureInPicture,
        )

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

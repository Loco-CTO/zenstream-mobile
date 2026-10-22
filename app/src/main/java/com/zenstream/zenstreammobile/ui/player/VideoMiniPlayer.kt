package com.zenstream.zenstreammobile.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.zenstream.zenstreammobile.VideoPlaybackSessions
import com.zenstream.zenstreammobile.VideoPresentation
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.launchPlayback
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.PlaybackTrackSelection
import com.zenstream.zenstreammobile.ui.screens.PlaybackScreen
import kotlin.math.min
import kotlinx.coroutines.launch

internal data class MiniVideoBounds(val width: Float, val height: Float, val x: Float, val y: Float)

internal fun miniVideoBounds(
    width: Float,
    screenWidth: Float,
    screenHeight: Float,
    right: Boolean,
    bottom: Boolean,
    padding: Float,
): MiniVideoBounds {
    val maximum =
        min(
            (screenWidth - 2 * padding).coerceAtLeast(1f),
            (screenHeight - 2 * padding).coerceAtLeast(1f) * 16f / 9f,
        )
    val w = width.coerceIn(min(160f, maximum), maximum)
    val h = w * 9f / 16f
    return MiniVideoBounds(
        w,
        h,
        if (right) screenWidth - padding - w else padding,
        if (bottom) screenHeight - padding - h else padding,
    )
}

@Composable
internal fun VideoMiniPlayerHost(
    repository: CatalogRepository,
    session: AuthSession?,
    systemPip: Boolean = false,
    sessionResolved: Boolean = true,
    onPipPlaybackChanged: (Boolean, Boolean) -> Unit = { _, _ -> },
) {
    val playback = VideoPlaybackSessions.active ?: return
    val context = LocalContext.current
    val currentSession = session ?: playback.session.takeUnless { sessionResolved }
    LaunchedEffect(currentSession, playback) {
        if (currentSession == null || !playback.matches(currentSession))
            VideoPlaybackSessions.close()
    }
    if (currentSession == null || !playback.matches(currentSession)) return
    if (playback.presentation != VideoPresentation.MINI) return
    DisposableEffect(playback) {
        onDispose {
            if (
                playback.presentation == VideoPresentation.TO_FULL &&
                    VideoPlaybackSessions.active === playback
            ) {
                Handler(Looper.getMainLooper()).post {
                    if (VideoPlaybackSessions.active === playback) {
                        val args = playback.args
                        launchPlayback(
                            context,
                            args.itemId,
                            args.itemName,
                            PlaybackTrackSelection(
                                args.audioStreamId,
                                args.subtitleStreamIndex,
                                args.hasSubtitleSelection,
                            ),
                        )
                    }
                }
            }
        }
    }
    VideoMiniPlayerFrame(systemPip = systemPip) {
        PlaybackScreen(
            repository = repository,
            session = currentSession,
            syncplay = repository.syncplayManager(currentSession),
            itemId = playback.args.itemId,
            initialItemName = playback.args.itemName,
            enterPictureInPicture = { false },
            shouldPauseForBackground = { true },
            onBack = { VideoPlaybackSessions.close() },
            pipInstanceId = "in-app-mini",
            isInPictureInPictureMode = systemPip,
            onPictureInPicturePlaybackChanged = onPipPlaybackChanged,
            miniPlayer = !systemPip,
            onRestore = { playback.presentation = VideoPresentation.TO_FULL },
            playbackOwner = playback,
        )
    }
}

@Composable
internal fun VideoMiniPlayerFrame(systemPip: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember {
        context.applicationContext.getSharedPreferences("video_miniplayer", Context.MODE_PRIVATE)
    }
    var width by remember {
        mutableFloatStateOf(
            preferences.getFloat("width_dp", 220f).takeIf { it.isFinite() && it > 0 } ?: 220f
        )
    }
    var right by remember { mutableStateOf(preferences.getBoolean("right", true)) }
    var bottom by remember { mutableStateOf(preferences.getBoolean("bottom", true)) }
    var gesturePosition by remember { mutableStateOf<Offset?>(null) }
    val density = LocalDensity.current
    val gestureScope = rememberCoroutineScope()
    BoxWithConstraints(
        if (systemPip) Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding()
    ) {
        val screenWidth = maxWidth.value
        val screenHeight = maxHeight.value
        val bounds = miniVideoBounds(width, screenWidth, screenHeight, right, bottom, 12f)
        val animatedX = remember { Animatable(bounds.x) }
        val animatedY = remember { Animatable(bounds.y) }
        LaunchedEffect(gesturePosition == null, bounds.x, bounds.y) {
            if (gesturePosition == null)
                kotlinx.coroutines.coroutineScope {
                    launch {
                        animatedX.animateTo(
                            bounds.x,
                            tween(280, easing = CubicBezierEasing(.22f, .61f, .36f, 1f)),
                        )
                    }
                    launch {
                        animatedY.animateTo(
                            bounds.y,
                            tween(280, easing = CubicBezierEasing(.22f, .61f, .36f, 1f)),
                        )
                    }
                }
        }

        val x = gesturePosition?.x ?: animatedX.value
        val y = gesturePosition?.y ?: animatedY.value
        Box(
            Modifier.then(
                    if (systemPip) Modifier.fillMaxSize()
                    else
                        Modifier.offset {
                                IntOffset(
                                    with(density) { x.dp.roundToPx() },
                                    with(density) { y.dp.roundToPx() },
                                )
                            }
                            .size(bounds.width.dp, bounds.height.dp)
                )
                .testTag("video-mini-player")
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(screenWidth, screenHeight, systemPip) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        if (systemPip) return@awaitEachGesture
                        var transformed = false
                        var accumulated = Offset.Zero
                        var accumulatedZoom = 1f
                        var current =
                            miniVideoBounds(width, screenWidth, screenHeight, right, bottom, 12f)
                        var position = Offset(animatedX.value, animatedY.value)
                        do {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan()
                            val zoom = event.calculateZoom()
                            accumulated += pan
                            accumulatedZoom *= zoom
                            if (
                                !transformed &&
                                    (accumulated.getDistance() > viewConfiguration.touchSlop ||
                                        kotlin.math.abs(1 - accumulatedZoom) *
                                            current.width *
                                            density.density > viewConfiguration.touchSlop)
                            )
                                transformed = true
                            if (transformed) {
                                current =
                                    miniVideoBounds(
                                        current.width * zoom,
                                        screenWidth,
                                        screenHeight,
                                        right,
                                        bottom,
                                        12f,
                                    )
                                width = current.width
                                position =
                                    Offset(
                                        (position.x + pan.x / density.density).coerceIn(
                                            12f,
                                            (screenWidth - current.width - 12f).coerceAtLeast(12f),
                                        ),
                                        (position.y + pan.y / density.density).coerceIn(
                                            12f,
                                            (screenHeight - current.height - 12f).coerceAtLeast(
                                                12f
                                            ),
                                        ),
                                    )
                                gesturePosition = position
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (transformed) {
                            gestureScope.launch {
                                animatedX.snapTo(position.x)
                                animatedY.snapTo(position.y)
                                right = position.x + current.width / 2 >= screenWidth / 2
                                bottom = position.y + current.height / 2 >= screenHeight / 2
                                preferences
                                    .edit()
                                    .putFloat("width_dp", width)
                                    .putBoolean("right", right)
                                    .putBoolean("bottom", bottom)
                                    .apply()
                                gesturePosition = null
                            }
                        } else gesturePosition = null
                    }
                }
        ) {
            content()
        }
    }
}

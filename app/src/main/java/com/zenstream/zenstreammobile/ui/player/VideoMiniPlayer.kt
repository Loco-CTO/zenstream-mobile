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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val MIN_VIDEO_MINI_PLAYER_WIDTH_DP = 192f

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
    val w = width.coerceIn(min(MIN_VIDEO_MINI_PLAYER_WIDTH_DP, maximum), maximum)
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
    val profile = playback.profile
    val retainedAllowed by
        produceState<Boolean?>(if (profile == null) false else null, playback, session) {
            val runtime = repository.offline
            if (profile != null && runtime != null) {
                combine(runtime.catalogRevision, runtime.connection.state) { _, policy -> policy }
                    .collect { policy ->
                        value =
                            policy.profile == profile.id &&
                                runtime.database.offline().profile(profile.id)?.locked == false &&
                                (session == null || playback.matches(session))
                    }
            }
        }
    val authorized =
        retainedAllowed == true ||
            (profile == null && currentSession != null && playback.matches(currentSession))
    LaunchedEffect(currentSession, playback, retainedAllowed) {
        if (retainedAllowed != null && !authorized) VideoPlaybackSessions.close()
    }
    if (!authorized) return
    if (
        playback.presentation != VideoPresentation.TO_MINI &&
            playback.presentation != VideoPresentation.MINI
    ) {
        return
    }
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
                            args.offlineProfileId,
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
            syncplay =
                currentSession
                    ?.takeIf { playback.args.offlineProfileId == null }
                    ?.let(repository::syncplayManager),
            offlineProfileId = profile?.id,
            offlineOnly = playback.args.offlineProfileId != null,
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
    var gestureBounds by remember { mutableStateOf<MiniVideoBounds?>(null) }
    var isGesturing by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val gestureScope = rememberCoroutineScope()
    BoxWithConstraints(
        if (systemPip) Modifier.fillMaxSize() else Modifier.fillMaxSize().safeDrawingPadding()
    ) {
        val screenWidth = maxWidth.value
        val screenHeight = maxHeight.value
        val bounds = miniVideoBounds(width, screenWidth, screenHeight, right, bottom, 12f)
        val latestBounds by rememberUpdatedState(bounds)
        val animatedX = remember { Animatable(bounds.x) }
        val animatedY = remember { Animatable(bounds.y) }
        LaunchedEffect(isGesturing, bounds.x, bounds.y) {
            if (!isGesturing)
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

        if (systemPip) {
            Box(
                Modifier.fillMaxSize().testTag("video-mini-player").clip(RoundedCornerShape(12.dp))
            ) {
                content()
            }
        } else {
            val frameModifier =
                Modifier.offset {
                        val displayed =
                            gestureBounds
                                ?: MiniVideoBounds(
                                    bounds.width,
                                    bounds.height,
                                    animatedX.value,
                                    animatedY.value,
                                )
                        IntOffset(
                            with(density) { displayed.x.dp.roundToPx() },
                            with(density) { displayed.y.dp.roundToPx() },
                        )
                    }
                    .pointerInput(screenWidth, screenHeight) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            val initialBounds =
                                gestureBounds
                                    ?: latestBounds.copy(x = animatedX.value, y = animatedY.value)
                            var transformed = false
                            var accumulated = Offset.Zero
                            var accumulatedZoom = 1f
                            var current = initialBounds
                            var position = Offset(initialBounds.x, initialBounds.y)
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
                                ) {
                                    transformed = true
                                    isGesturing = true
                                }
                                if (transformed) {
                                    val resized =
                                        miniVideoBounds(
                                            current.width * zoom,
                                            screenWidth,
                                            screenHeight,
                                            right,
                                            bottom,
                                            12f,
                                        )
                                    val x =
                                        (position.x +
                                                pan.x / this.density +
                                                (current.width - resized.width) / 2f)
                                            .coerceIn(
                                                12f,
                                                (screenWidth - resized.width - 12f).coerceAtLeast(
                                                    12f
                                                ),
                                            )
                                    val y =
                                        (position.y +
                                                pan.y / this.density +
                                                (current.height - resized.height) / 2f)
                                            .coerceIn(
                                                12f,
                                                (screenHeight - resized.height - 12f).coerceAtLeast(
                                                    12f
                                                ),
                                            )
                                    current = resized.copy(x = x, y = y)
                                    position = Offset(x, y)
                                    gestureBounds = current
                                    event.changes.forEach { it.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (transformed) {
                                gestureScope.launch {
                                    animatedX.snapTo(position.x)
                                    animatedY.snapTo(position.y)
                                    width = current.width
                                    right = position.x + current.width / 2 >= screenWidth / 2
                                    bottom = position.y + current.height / 2 >= screenHeight / 2
                                    preferences
                                        .edit()
                                        .putFloat("width_dp", width)
                                        .putBoolean("right", right)
                                        .putBoolean("bottom", bottom)
                                        .apply()
                                    gestureBounds = null
                                    isGesturing = false
                                }
                            } else gestureBounds = null
                        }
                    }
                    .testTag("video-mini-player")
            val baseWidthPx = with(density) { MIN_VIDEO_MINI_PLAYER_WIDTH_DP.dp.roundToPx() }
            val baseHeightPx =
                with(density) { (MIN_VIDEO_MINI_PLAYER_WIDTH_DP * 9f / 16f).dp.roundToPx() }

            Layout(
                content = {
                    Box(
                        Modifier.size(
                                MIN_VIDEO_MINI_PLAYER_WIDTH_DP.dp,
                                (MIN_VIDEO_MINI_PLAYER_WIDTH_DP * 9f / 16f).dp,
                            )
                            .graphicsLayer {
                                val displayWidth = gestureBounds?.width ?: bounds.width
                                val scale = displayWidth / MIN_VIDEO_MINI_PLAYER_WIDTH_DP
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin.Center
                            }
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        content()
                    }
                },
                modifier = frameModifier,
            ) { measurables, _ ->
                val displayWidth = gestureBounds?.width ?: bounds.width
                val frameWidthPx = with(density) { displayWidth.dp.roundToPx() }
                val frameHeightPx = with(density) { (displayWidth * 9f / 16f).dp.roundToPx() }
                val player =
                    measurables.single().measure(Constraints.fixed(baseWidthPx, baseHeightPx))
                layout(frameWidthPx, frameHeightPx) {
                    player.placeRelative(
                        (frameWidthPx - baseWidthPx) / 2,
                        (frameHeightPx - baseHeightPx) / 2,
                    )
                }
            }
        }
    }
}

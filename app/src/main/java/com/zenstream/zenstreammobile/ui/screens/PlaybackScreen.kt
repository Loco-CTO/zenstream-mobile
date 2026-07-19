package com.zenstream.zenstreammobile.ui.screens

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.selectable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.JellyfinRepository
import com.zenstream.zenstreammobile.data.JellyfinApi
import com.zenstream.zenstreammobile.data.trickplayPreview
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.MediaStream
import com.zenstream.zenstreammobile.model.PlaybackSegment
import com.zenstream.zenstreammobile.model.PlaybackSegmentType
import com.zenstream.zenstreammobile.model.TrickplayPreview
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.zenstream.zenstreammobile.ui.PlaybackViewModel
import com.zenstream.zenstreammobile.ui.player.SubtitleOverlay
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun PlaybackScreen(
    repository: JellyfinRepository,
    session: AuthSession,
    itemId: String,
    initialItemName: String = "",
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val vm: PlaybackViewModel = viewModel(
        key = "playback-${session.userId}-$itemId",
        factory = PlaybackViewModel.Factory(repository, session, itemId, context),
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    var controlsVisible by remember { mutableStateOf(false) }
    var controlsLocked by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var seekFeedback by remember { mutableStateOf<SeekFeedback?>(null) }
    var subtitlePositionSeconds by remember(vm) { mutableStateOf(0.0) }
    var timelineScrub by remember { mutableStateOf<TimelineScrub?>(null) }
    var previewUnavailable by remember { mutableStateOf(false) }
    var surfaceDragPosition by remember { mutableStateOf<Double?>(null) }
    var surfacePreviewUnavailable by remember { mutableStateOf(false) }

    DisposableEffect(vm, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                vm.flushProgress()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.flushProgress()
        }
    }

    LaunchedEffect(controlsVisible, controlsLocked, sheet, state.engine.isPlaying) {
        if (shouldAutoHidePlaybackControls(controlsVisible, controlsLocked, sheet != null, state.engine.isPlaying)) {
            delay(4_500)
            controlsVisible = false
        }
    }

    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(SEEK_FEEDBACK_VISIBLE_MILLIS)
            seekFeedback = null
        }
    }

    LaunchedEffect(vm) {
        while (isActive) {
            withFrameNanos { }
            subtitlePositionSeconds = vm.subtitlePositionSeconds()
        }
    }

    val playerView = remember(state.loading, state.engineType) {
        if (!state.loading) vm.createView(context) else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (playerView != null) {
            AndroidView(
                factory = { playerView },
                modifier = Modifier.fillMaxSize(),
            )
        }

        PlaybackGestureLayer(
            modifier = Modifier.fillMaxSize(),
            controlsLocked = controlsLocked,
            positionSeconds = state.engine.positionSeconds,
            durationSeconds = state.engine.durationSeconds,
            onToggleControls = { controlsVisible = !controlsVisible },
            onSeekBy = vm::seekBy,
            onSeekFeedback = { seekFeedback = it },
            onSurfaceDragStart = {
                seekFeedback = null
                surfacePreviewUnavailable = false
                surfaceDragPosition = it
            },
            onSurfaceDragChanged = {
                seekFeedback = null
                surfaceDragPosition = it
            },
            onSurfaceDragEnd = {
                vm.seekTo(it)
                surfaceDragPosition = null
            },
            onSurfaceDragCancel = { surfaceDragPosition = null },
        )

        seekFeedback?.let { feedback ->
            SeekFeedbackOverlay(
                feedback = feedback,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        SubtitleOverlay(
            cues = state.activeCuesAt(subtitlePositionSeconds),
            style = state.subtitleStyle,
            bottomPadding = if (controlsVisible && !controlsLocked) 128.dp else 48.dp,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        if (state.loading) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (state.error != null && !state.loading) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.error ?: stringResourceCompat(R.string.media_playback_failed), color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = onBack) { Text(stringResourceCompat(R.string.back)) }
            }
        }

        if (controlsVisible || controlsLocked) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = .78f), Color.Black.copy(alpha = 0f))
                        )
                    )
                    .padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(onClick = { vm.flushProgress(); onBack() }) {
                        Icon(painterResource(LucideR.drawable.lucide_ic_arrow_left), stringResourceCompat(R.string.back), tint = Color.White)
                    }
                    Text(
                        playbackTitle(state, initialItemName),
                        color = Color.White,
                        maxLines = 1,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { controlsLocked = !controlsLocked; sheet = null }) {
                            Icon(
                                painter = painterResource(if (controlsLocked) LucideR.drawable.lucide_ic_lock else LucideR.drawable.lucide_ic_lock_open),
                                stringResourceCompat(if (controlsLocked) R.string.player_unlock else R.string.player_lock),
                                tint = Color.White,
                            )
                        }
                        if (!controlsLocked) {
                            PlayerMenuButton(LucideR.drawable.lucide_ic_picture_in_picture, stringResourceCompat(R.string.player_pip), enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { enterPip(context) }
                            PlayerMenuButton(LucideR.drawable.lucide_ic_gauge, stringResourceCompat(R.string.player_speed)) { sheet = PlayerSheet.Speed }
                            if (shouldShowAudioSelector(state.playback?.audio.orEmpty().size)) {
                                PlayerMenuButton(LucideR.drawable.lucide_ic_audio_lines, stringResourceCompat(R.string.audio_track)) { sheet = PlayerSheet.Audio }
                            }
                            if (shouldShowSubtitleSelector(state.playback?.subtitles.orEmpty().size)) {
                                PlayerMenuButton(LucideR.drawable.lucide_ic_captions, stringResourceCompat(R.string.subtitle_track)) { sheet = PlayerSheet.Subtitles }
                            }
                            PlayerMenuButton(LucideR.drawable.lucide_ic_settings, stringResourceCompat(R.string.player_quality)) { sheet = PlayerSheet.Quality }
                        }
                    }
                }
            }
            if (controlsVisible && !controlsLocked) {
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerMenuButton(LucideR.drawable.lucide_ic_skip_back, stringResourceCompat(R.string.player_previous), enabled = false) {}
                    PlayerMenuButton(LucideR.drawable.lucide_ic_rewind, stringResourceCompat(R.string.player_seek_back)) { vm.seekBy(-10.0) }
                    Surface(
                        onClick = vm::togglePlay,
                        modifier = Modifier.size(64.dp),
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = .58f),
                        contentColor = Color.White,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(if (state.engine.isPlaying) LucideR.drawable.lucide_ic_pause else LucideR.drawable.lucide_ic_play),
                                stringResourceCompat(if (state.engine.isPlaying) R.string.pause else R.string.play),
                                modifier = Modifier.size(28.dp),
                                tint = Color.White,
                            )
                        }
                    }
                    PlayerMenuButton(LucideR.drawable.lucide_ic_fast_forward, stringResourceCompat(R.string.player_seek_forward)) { vm.seekBy(10.0) }
                    PlayerMenuButton(LucideR.drawable.lucide_ic_skip_forward, stringResourceCompat(R.string.player_next), enabled = false) {}
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = .8f))
                            )
                        )
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    val displayedPosition = timelineScrub?.positionSeconds ?: state.engine.positionSeconds
                    val preview = timelineScrub?.let { scrub ->
                        trickplayPreview(
                            session = session,
                            itemId = itemId,
                            source = state.playback?.source,
                            timeSeconds = state.mediaOriginSeconds + scrub.positionSeconds,
                        )
                    }
                    Text(
                        "${formatTime(displayedPosition)} / ${formatTime(state.engine.durationSeconds)}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    PlaybackProgress(
                        session = session,
                        positionSeconds = displayedPosition,
                        durationSeconds = state.engine.durationSeconds,
                        bufferedSeconds = state.engine.bufferedSeconds,
                        segments = state.segments,
                        scrub = timelineScrub,
                        preview = preview.takeUnless { previewUnavailable },
                        onScrubStart = {
                            previewUnavailable = false
                            timelineScrub = it
                        },
                        onScrubChanged = { timelineScrub = it },
                        onScrubEnd = {
                            vm.seekTo(it.positionSeconds)
                            timelineScrub = null
                        },
                        onPreviewError = { previewUnavailable = true },
                    )
                }
            }
        }

        if (!controlsLocked) {
            state.activeSegmentAt(state.engine.positionSeconds)?.let { segment ->
                Surface(
                    onClick = { vm.skipSegment(segment) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 20.dp, bottom = if (controlsVisible) 86.dp else 24.dp),
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = .58f),
                    contentColor = Color.White,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(LucideR.drawable.lucide_ic_fast_forward),
                            contentDescription = stringResourceCompat(
                                if (segment.type == PlaybackSegmentType.INTRO) R.string.skip_intro else R.string.skip_outro
                            ),
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            stringResourceCompat(if (segment.type == PlaybackSegmentType.INTRO) R.string.skip_intro else R.string.skip_outro),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        surfaceDragPosition?.let { targetPosition ->
            val preview = trickplayPreview(
                session = session,
                itemId = itemId,
                source = state.playback?.source,
                timeSeconds = state.mediaOriginSeconds + targetPosition,
            ).takeUnless { surfacePreviewUnavailable }
            SurfaceTrickplayOverlay(
                session = session,
                positionSeconds = targetPosition,
                durationSeconds = state.engine.durationSeconds,
                preview = preview,
                onPreviewError = { surfacePreviewUnavailable = true },
                modifier = Modifier
                    .align(Alignment.Center)
                    .zIndex(20f),
            )
        }

        PlayerBottomSheet(
            sheet = sheet,
            selectedSubtitle = state.selectedSubtitle,
            selectedAudio = state.selectedAudio,
            selectedQuality = state.selectedQuality,
            audio = state.playback?.audio.orEmpty(),
            subtitles = state.playback?.subtitles.orEmpty(),
            qualities = state.playback?.qualities.orEmpty(),
            speed = state.engine.speed,
            onDismiss = { sheet = null },
            onSubtitle = { vm.chooseSubtitle(it); sheet = null },
            onAudio = { vm.chooseAudio(it); sheet = null },
            onQuality = { vm.chooseQuality(it); sheet = null },
            onSpeed = { vm.setSpeed(it); sheet = null },
        )
    }
}

internal enum class SeekDirection {
    BACKWARD,
    FORWARD,
}

internal data class SeekFeedback(
    val direction: SeekDirection,
    val seconds: Int,
)

internal const val PLAYBACK_TIMELINE_CONTROLS_GAP_DP = 16
private const val QUICK_SEEK_SECONDS = 5.0
private const val SEEK_FEEDBACK_VISIBLE_MILLIS = 800L

@Composable
internal fun PlaybackGestureLayer(
    modifier: Modifier = Modifier,
    controlsLocked: Boolean,
    positionSeconds: Double,
    durationSeconds: Double,
    onToggleControls: () -> Unit,
    onSeekBy: (Double) -> Unit,
    onSeekFeedback: (SeekFeedback) -> Unit,
    onSurfaceDragStart: (Double) -> Unit = {},
    onSurfaceDragChanged: (Double) -> Unit = {},
    onSurfaceDragEnd: (Double) -> Unit = {},
    onSurfaceDragCancel: () -> Unit = {},
) {
    val quickControlsDescription = stringResourceCompat(R.string.player_quick_controls)
    val currentPosition = rememberUpdatedState(positionSeconds)
    val currentDuration = rememberUpdatedState(durationSeconds)
    val currentLocked = rememberUpdatedState(controlsLocked)
    val toggleControls = rememberUpdatedState(onToggleControls)
    val seekBy = rememberUpdatedState(onSeekBy)
    val showFeedback = rememberUpdatedState(onSeekFeedback)

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                var dragStartPosition = 0.0
                var dragDistancePixels = 0f
                var dragTargetPosition = 0.0
                var dragActive = false

                detectHorizontalDragGestures(
                    onDragStart = {
                        dragStartPosition = currentPosition.value.coerceAtLeast(0.0)
                        dragDistancePixels = 0f
                        dragTargetPosition = dragStartPosition
                        dragActive = currentDuration.value.isFinite() && currentDuration.value > 0.0
                        if (dragActive) onSurfaceDragStart(dragTargetPosition)
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragDistancePixels += dragAmount
                        val duration = currentDuration.value
                        if (dragActive && duration.isFinite() && duration > 0.0) {
                            val delta = dragSeekDeltaSeconds(
                                dragDistancePixels = dragDistancePixels,
                                playerWidthPixels = size.width,
                                durationSeconds = duration,
                            )
                            dragTargetPosition = clampSeekTarget(dragStartPosition + delta, duration)
                            onSurfaceDragChanged(dragTargetPosition)
                        }
                    },
                    onDragEnd = {
                        if (dragActive) onSurfaceDragEnd(dragTargetPosition)
                        dragActive = false
                    },
                    onDragCancel = {
                        if (dragActive) onSurfaceDragCancel()
                        dragActive = false
                    },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        val delta = quickSeekDeltaForTap(offset.x, size.width)
                        seekBy.value(delta)
                        showFeedback.value(
                            SeekFeedback(
                                direction = if (delta < 0.0) SeekDirection.BACKWARD else SeekDirection.FORWARD,
                                seconds = feedbackSeconds(delta),
                            )
                        )
                    },
                    onTap = {
                        if (!currentLocked.value) toggleControls.value()
                    },
                )
            }
            .semantics {
                contentDescription = quickControlsDescription
            },
    )
}

@Composable
internal fun SeekFeedbackOverlay(
    feedback: SeekFeedback,
    modifier: Modifier = Modifier,
) {
    val isBackward = feedback.direction == SeekDirection.BACKWARD
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = Color.Black.copy(alpha = .72f),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(
                    if (isBackward) LucideR.drawable.lucide_ic_rewind
                    else LucideR.drawable.lucide_ic_fast_forward
                ),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = stringResourceCompat(
                    if (isBackward) R.string.player_quick_seek_back else R.string.player_quick_seek_forward,
                    feedback.seconds,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

internal fun dragSeekDeltaSeconds(
    dragDistancePixels: Float,
    playerWidthPixels: Int,
    durationSeconds: Double,
): Double {
    if (playerWidthPixels <= 0 || !durationSeconds.isFinite() || durationSeconds <= 0.0) return 0.0
    return dragDistancePixels.toDouble() / playerWidthPixels.toDouble() * durationSeconds
}

internal fun quickSeekDeltaForTap(tapX: Float, playerWidthPixels: Int): Double =
    if (tapX < playerWidthPixels / 2f) -QUICK_SEEK_SECONDS else QUICK_SEEK_SECONDS

internal fun clampSeekTarget(positionSeconds: Double, durationSeconds: Double): Double =
    positionSeconds.coerceIn(0.0, durationSeconds.coerceAtLeast(0.0))

internal fun feedbackSeconds(deltaSeconds: Double): Int =
    kotlin.math.abs(deltaSeconds).toInt().coerceAtLeast(1)

internal enum class PlayerSheet { Audio, Subtitles, Speed, Quality }

private data class TimelineScrub(
    val positionSeconds: Double,
    val fraction: Float,
)

internal fun trickplaySpriteSize(
    cellWidth: androidx.compose.ui.unit.Dp,
    cellHeight: androidx.compose.ui.unit.Dp,
    columns: Int,
    rows: Int,
): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> =
    cellWidth * columns to cellHeight * rows

internal fun timelinePositionAt(x: Float, width: Float, duration: Double): Double {
    val safeWidth = width.coerceAtLeast(1f)
    val safeDuration = duration.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    return (x / safeWidth).coerceIn(0f, 1f) * safeDuration
}

@Composable
private fun PlaybackProgress(
    session: AuthSession,
    positionSeconds: Double,
    durationSeconds: Double,
    bufferedSeconds: Double,
    segments: List<PlaybackSegment>,
    scrub: TimelineScrub?,
    preview: TrickplayPreview?,
    onScrubStart: (TimelineScrub) -> Unit,
    onScrubChanged: (TimelineScrub) -> Unit,
    onScrubEnd: (TimelineScrub) -> Unit,
    onPreviewError: () -> Unit,
) {
    val duration = durationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: 0.1
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 12.dp)
            .pointerInput(duration) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun scrubFrom(x: Float): TimelineScrub {
                        val fraction = (x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                        return TimelineScrub(
                            positionSeconds = timelinePositionAt(x, size.width.toFloat(), duration),
                            fraction = fraction,
                        )
                    }
                    var latest = scrubFrom(down.position.x)
                    onScrubStart(latest)
                    drag(down.id) { change ->
                        change.consume()
                        latest = scrubFrom(change.position.x)
                        onScrubChanged(latest)
                    }
                    onScrubEnd(latest)
                }
            },
    ) {
        val previewWidth = preview?.let {
            val scale = minOf(1f, 240f / it.width, 150f / it.height)
            (it.width * scale).dp
        }
        val previewHeight = preview?.let {
            val scale = minOf(1f, 240f / it.width, 150f / it.height)
            (it.height * scale).dp
        }
        if (preview != null && scrub != null && previewWidth != null && previewHeight != null) {
            TrickplayBubble(
                preview = preview,
                position = scrub,
                session = session,
                width = previewWidth,
                height = previewHeight,
                onError = onPreviewError,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(
                        x = (scrub.fraction * maxWidth.value - previewWidth.value / 2f)
                            .coerceIn(0f, (maxWidth - previewWidth).value)
                            .dp,
                        y = -(previewHeight + 8.dp),
                    ),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val trackHeight = 3.dp.toPx()
            val trackTop = (size.height - trackHeight) / 2f
            val trackWidth = size.width
            fun xAt(seconds: Double): Float =
                (seconds.coerceIn(0.0, duration) / duration * trackWidth).toFloat()

            drawRoundRect(
                color = Color.White.copy(alpha = .25f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, trackTop),
                size = androidx.compose.ui.geometry.Size(trackWidth, trackHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
            )
            val bufferedWidth = xAt(bufferedSeconds)
            if (bufferedWidth > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = .42f),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, trackTop),
                    size = androidx.compose.ui.geometry.Size(bufferedWidth, trackHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
                )
            }
            segments.forEach { segment ->
                val left = xAt(segment.startSeconds)
                val right = xAt(segment.endSeconds)
                if (right > left) {
                    drawRoundRect(
                        color = if (segment.type == PlaybackSegmentType.INTRO) {
                            Color(0xFF60A5FA)
                        } else {
                            Color(0xFFF59E0B)
                        },
                        topLeft = androidx.compose.ui.geometry.Offset(left, trackTop),
                        size = androidx.compose.ui.geometry.Size(right - left, trackHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
                    )
                }
            }
            val progressWidth = xAt(positionSeconds)
            if (progressWidth > 0f) {
                drawRoundRect(
                    color = Color(0xFFA78BFA),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, trackTop),
                    size = androidx.compose.ui.geometry.Size(progressWidth, trackHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(trackHeight / 2f),
                )
            }
            drawCircle(
                color = Color(0xFFA78BFA),
                radius = 4.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(progressWidth, size.height / 2f),
            )
        }
    }
}

@Composable
internal fun SurfaceTrickplayOverlay(
    session: AuthSession,
    positionSeconds: Double,
    durationSeconds: Double,
    preview: TrickplayPreview?,
    onPreviewError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val maxPreviewWidth = (maxWidth - 32.dp).coerceAtLeast(1.dp)
        val previewSize = preview?.let {
            val scale = minOf(
                1f,
                360f / it.width.coerceAtLeast(1),
                203f / it.height.coerceAtLeast(1),
                maxPreviewWidth.value / it.width.coerceAtLeast(1),
            )
            (it.width * scale).dp to (it.height * scale).dp
        }
        val accessibilityDescription = stringResourceCompat(
            R.string.player_drag_seek_preview,
            formatTime(positionSeconds),
            formatTime(durationSeconds),
        )
        Surface(
            modifier = Modifier
                .testTag("surface-trickplay-preview")
                .semantics {
                    contentDescription = accessibilityDescription
                },
            shape = RoundedCornerShape(18.dp),
            color = Color.Black.copy(alpha = .78f),
            contentColor = Color.White,
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (preview != null && previewSize != null) {
                    TrickplaySpriteFrame(
                        preview = preview,
                        session = session,
                        width = previewSize.first,
                        height = previewSize.second,
                        contentDescription = stringResourceCompat(
                            R.string.player_timeline_preview,
                            formatTime(positionSeconds),
                        ),
                        onError = onPreviewError,
                    )
                }
                Text(
                    "${formatTime(positionSeconds)} / ${formatTime(durationSeconds)}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun TrickplayBubble(
    preview: TrickplayPreview,
    position: TimelineScrub,
    session: AuthSession,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cellWidth = width
    val cellHeight = height
    Column(
        modifier = modifier
            // PlaybackProgress is only 48.dp tall. The web player renders this
            // bubble out of flow, so do not let that track constraint collapse
            // the preview viewport or its caption.
            .requiredWidth(cellWidth)
            .wrapContentHeight(unbounded = true)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = .9f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TrickplaySpriteFrame(
            preview = preview,
            session = session,
            width = cellWidth,
            height = cellHeight,
            contentDescription = stringResourceCompat(
                R.string.player_timeline_preview,
                formatTime(position.positionSeconds),
            ),
            onError = onError,
            modifier = Modifier.clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
        )
        Text(
            formatTime(position.positionSeconds),
            color = Color.White.copy(alpha = .85f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun TrickplaySpriteFrame(
    preview: TrickplayPreview,
    session: AuthSession,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    contentDescription: String,
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val request = remember(preview.url, session.token) {
        ImageRequest.Builder(context)
            .data(preview.url)
            .httpHeaders(
                NetworkHeaders.Builder()
                    .set("Authorization", JellyfinApi.authorizationHeader(session.token))
                    .build(),
            )
            .build()
    }
    val spriteSize = trickplaySpriteSize(width, height, preview.columns, preview.rows)
    Layout(
        modifier = modifier
            .requiredSize(width, height)
            .clip(RoundedCornerShape(6.dp)),
        content = {
            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier,
                onError = { onError() },
            )
        },
    ) { measurables, _ ->
        val frameWidth = width.roundToPx()
        val frameHeight = height.roundToPx()
        val sheetWidth = spriteSize.first.roundToPx()
        val sheetHeight = spriteSize.second.roundToPx()
        val image = measurables.single().measure(Constraints.fixed(sheetWidth, sheetHeight))
        layout(frameWidth, frameHeight) {
            image.place(
                -width.roundToPx() * preview.cellX,
                -height.roundToPx() * preview.cellY,
            )
        }
    }
}

@Composable
private fun PlayerMenuButton(
    @androidx.annotation.DrawableRes icon: Int,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(painterResource(icon), label, tint = if (enabled) Color.White else Color.White.copy(alpha = .3f))
    }
}

private val playbackSpeedOptions = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)

private val PlayerSheetSurface = Color(0xFF1B1B1F)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PlayerBottomSheet(
    sheet: PlayerSheet?,
    selectedSubtitle: Int?,
    selectedAudio: Int?,
    selectedQuality: Int,
    audio: List<MediaStream>,
    subtitles: List<MediaStream>,
    qualities: List<Int>,
    speed: Float,
    onDismiss: () -> Unit,
    onSubtitle: (Int?) -> Unit,
    onAudio: (MediaStream) -> Unit,
    onQuality: (Int) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    if (sheet == null) return

    val title = when (sheet) {
        PlayerSheet.Audio -> stringResourceCompat(R.string.audio_track)
        PlayerSheet.Subtitles -> stringResourceCompat(R.string.subtitle_track)
        PlayerSheet.Speed -> stringResourceCompat(R.string.player_speed)
        PlayerSheet.Quality -> stringResourceCompat(R.string.player_quality)
    }
    val icon = when (sheet) {
        PlayerSheet.Audio -> LucideR.drawable.lucide_ic_audio_lines
        PlayerSheet.Subtitles -> LucideR.drawable.lucide_ic_captions
        PlayerSheet.Speed -> LucideR.drawable.lucide_ic_gauge
        PlayerSheet.Quality -> LucideR.drawable.lucide_ic_settings
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = .72f),
        dragHandle = null,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = PlayerSheetSurface,
                contentColor = Color.White,
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp, bottom = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = .36f))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                            .semantics { heading() },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                        )
                    }

                    when (sheet) {
                        PlayerSheet.Audio -> audio.forEach { stream ->
                            PlayerOptionRow(
                                label = streamTitle(stream, R.string.player_audio_track_format),
                                selected = selectedAudio == stream.index,
                                onClick = { onAudio(stream) },
                            )
                        }
                        PlayerSheet.Subtitles -> {
                            PlayerOptionRow(
                                label = stringResourceCompat(R.string.subtitles_off),
                                selected = selectedSubtitle == null,
                                onClick = { onSubtitle(null) },
                            )
                            subtitles.forEach { stream ->
                                PlayerOptionRow(
                                    label = streamTitle(stream, R.string.player_subtitle_track_format),
                                    selected = selectedSubtitle == stream.index,
                                    onClick = { onSubtitle(stream.index) },
                                )
                            }
                        }
                        PlayerSheet.Speed -> playbackSpeedOptions.forEach { value ->
                            PlayerOptionRow(
                                label = playbackSpeedLabel(value),
                                selected = speed == value,
                                onClick = { onSpeed(value) },
                            )
                        }
                        PlayerSheet.Quality -> qualities.forEach { value ->
                            PlayerOptionRow(
                                label = playbackQualityLabel(value),
                                selected = selectedQuality == value,
                                onClick = { onQuality(value) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_check),
                contentDescription = stringResourceCompat(R.string.player_selected_option),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun streamTitle(stream: MediaStream, fallback: Int): String =
    stream.displayTitle?.takeIf { it.isNotBlank() }
        ?: stream.language?.takeIf { it.isNotBlank() }
        ?: stringResourceCompat(fallback, stream.index)

@Composable
private fun playbackSpeedLabel(value: Float): String =
    stringResourceCompat(R.string.player_speed_value, formatPlaybackSpeedValue(value))

@Composable
private fun playbackQualityLabel(value: Int): String =
    if (value == 0) {
        stringResourceCompat(R.string.player_quality_auto)
    } else {
        stringResourceCompat(R.string.player_quality_bitrate, value / 1_000_000)
    }

internal fun formatPlaybackSpeedValue(value: Float): String =
    if (value % 1f == 0f) value.toInt().toString() else value.toString()

private fun enterPip(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        (context as? Activity)?.enterPictureInPictureMode(
            PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
        )
    }
}

private fun playbackTitle(
    state: com.zenstream.zenstreammobile.ui.PlaybackUiState,
    initialItemName: String,
): String {
    val item = state.playback?.item ?: return state.itemName.ifBlank { initialItemName }
    return if (item.parentIndexNumber != null && item.indexNumber != null) {
        "S${item.parentIndexNumber}:E${item.indexNumber} - ${item.name}"
    } else {
        item.name
    }
}

private fun formatTime(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

internal fun shouldAutoHidePlaybackControls(
    visible: Boolean,
    locked: Boolean,
    menuOpen: Boolean,
    isPlaying: Boolean,
): Boolean = visible && !locked && !menuOpen && isPlaying

internal fun shouldShowAudioSelector(trackCount: Int): Boolean = trackCount > 1
internal fun shouldShowSubtitleSelector(trackCount: Int): Boolean = trackCount > 0

@Composable
private fun stringResourceCompat(id: Int, vararg formatArgs: Any): String =
    androidx.compose.ui.res.stringResource(id, *formatArgs)

package com.zenstream.zenstreammobile.ui.startup

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.zenstream.zenstreammobile.R

private const val LogoEntryMillis = 175f
private const val BarStartMillis = 250f
private const val LogoSettleMillis = 510f
private const val ExitMillis = 260f
private const val MaxWaitMillis = 4_500f
private const val MaxFrameStepMillis = 40f
private const val SweepMillis = 790f
private val LogoEntryEasing = CubicBezierEasing(0.16f, 0.68f, 0.28f, 1f)
private val SettleEasing = CubicBezierEasing(0.34f, 0f, 0.22f, 1f)
private val BarEntryEasing = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)
private val SweepEasing = CubicBezierEasing(0.45f, 0f, 0.30f, 1f)
private val ExitEasing = CubicBezierEasing(0.28f, 0f, 0.24f, 1f)

private data class SplashFrame(
    val elapsed: Float = 0f,
    val exitElapsed: Float = 0f,
) {
    val handoff: Float
        get() = ExitEasing.transform(fraction(exitElapsed, start = 82f, end = ExitMillis))
}

@Composable
internal fun StartupSplash(
    active: Boolean,
    destinationReady: Boolean,
    onFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    val frame = remember { mutableStateOf(SplashFrame()) }
    Box(Modifier.fillMaxSize()) {
        val destinationMotion =
            if (active) {
                Modifier.clearAndSetSemantics {}
                    .graphicsLayer {
                        translationY = 12.dp.toPx() * (1f - frame.value.handoff)
                    }
            } else Modifier
        Box(Modifier.fillMaxSize().then(destinationMotion)) { content() }
        if (active) SplashArtwork(destinationReady, onFinished, frame)
    }
}

@Composable
private fun SplashArtwork(
    destinationReady: Boolean,
    onFinished: () -> Unit,
    frame: MutableState<SplashFrame>,
) {
    val ready = rememberUpdatedState(destinationReady)
    val finished = rememberUpdatedState(onFinished)
    val logo = painterResource(R.mipmap.zenstream_logo)
    val background = MaterialTheme.colorScheme.background
    val ink = MaterialTheme.colorScheme.onBackground

    LaunchedEffect(Unit) {
        val motion = coroutineContext[MotionDurationScale]
        var previousNanos = 0L
        var elapsed = 0f
        var realElapsed = 0f
        var exitStart: Float? = null
        while (true) {
            val durationScale = motion?.scaleFactor ?: 1f
            if (durationScale == 0f) break
            val now = withFrameNanos { it }
            if (previousNanos != 0L) {
                val deltaMillis = (now - previousNanos) / 1_000_000f
                realElapsed += deltaMillis
                // Limit motion jumps after a stalled frame. The wait limit uses wall time.
                elapsed += deltaMillis.coerceAtMost(MaxFrameStepMillis) / durationScale
            }
            previousNanos = now

            if (
                exitStart == null &&
                    ((elapsed >= LogoSettleMillis && ready.value) || realElapsed >= MaxWaitMillis)
            ) {
                exitStart = elapsed
            }
            val exitElapsed = exitStart?.let { elapsed - it } ?: 0f
            if (exitElapsed >= ExitMillis) break
            // Freeze the sweep position at the moment the completion starts.
            frame.value = SplashFrame(elapsed = exitStart ?: elapsed, exitElapsed = exitElapsed)
        }
        finished.value()
    }

    Canvas(
        Modifier.fillMaxSize().progressSemantics().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) awaitPointerEvent().changes.forEach { it.consume() }
            }
        }
    ) {
        val (elapsed, exitElapsed) = frame.value
        val entry = LogoEntryEasing.transform(fraction(elapsed, start = 0f, end = LogoEntryMillis))
        val entryAlpha = SettleEasing.transform(fraction(elapsed, start = 0f, end = 145f))
        val settle = SettleEasing.transform(fraction(elapsed, BarStartMillis, LogoSettleMillis))
        val logoExit = ExitEasing.transform(fraction(exitElapsed, start = 48f, end = 242f))
        drawRect(background, alpha = 1f - frame.value.handoff)

        val logoEdge = 96.dp.toPx()
        val logoCenter =
            center +
                Offset(
                    0f,
                    7.dp.toPx() * (1f - entry) - 8.dp.toPx() * settle - 16.dp.toPx() * logoExit,
                )
        val logoScale =
            lerp(0.95f, 1f, entry) * lerp(1f, 0.875f, settle) * lerp(1f, 0.91f, logoExit)
        scale(logoScale, pivot = logoCenter) {
            translate(logoCenter.x - logoEdge / 2f, logoCenter.y - logoEdge / 2f) {
                with(logo) { draw(Size(logoEdge, logoEdge), alpha = entryAlpha * (1f - logoExit)) }
            }
        }

        if (elapsed <= BarStartMillis) return@Canvas
        val barSlide = BarEntryEasing.transform(fraction(elapsed, BarStartMillis, end = 470f))
        val barFade = SettleEasing.transform(fraction(elapsed, BarStartMillis, end = 455f))
        val barExit = ExitEasing.transform(fraction(exitElapsed, start = 75f, end = 190f))
        val trackWidth = 100.dp.toPx() * lerp(1f, 0.68f, barExit)
        val trackHeight = 2.dp.toPx()
        val trackLeft = center.x - trackWidth / 2f
        val trackTop = center.y + lerp(70.dp.toPx(), 57.dp.toPx(), barSlide) - 6.dp.toPx() * barExit
        val radius = CornerRadius(trackHeight / 2f)
        val trackAlpha = barFade * (1f - barExit)
        drawRoundRect(
            ink,
            topLeft = Offset(trackLeft, trackTop),
            size = Size(trackWidth, trackHeight),
            cornerRadius = radius,
            alpha = 0.14f * trackAlpha,
        )

        val sweep = ((elapsed - BarStartMillis) % SweepMillis) / SweepMillis
        val leading = SweepEasing.transform(fraction(sweep, start = 0f, end = 0.78f))
        val trailing = SweepEasing.transform(fraction(sweep, start = 0.22f, end = 1f))
        val completion = BarEntryEasing.transform(fraction(exitElapsed, start = 0f, end = 82f))
        val start = lerp(trailing, 0f, completion)
        val end = lerp(leading, 1f, completion)
        drawRoundRect(
            ink,
            topLeft = Offset(trackLeft + trackWidth * start, trackTop),
            size = Size(trackWidth * (end - start), trackHeight),
            cornerRadius = radius,
            alpha = 0.88f * trackAlpha,
        )
    }
}

private fun fraction(value: Float, start: Float, end: Float): Float =
    ((value - start) / (end - start)).coerceIn(0f, 1f)

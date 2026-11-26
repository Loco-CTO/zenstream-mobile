package com.zenstream.zenstreammobile.ui.startup

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.zenstream.zenstreammobile.R

private const val IntroMillis = 520f
private const val ExitMillis = 210f
private const val MaxWaitMillis = 4_500f
private const val MaxFrameStepMillis = 40f
private const val SweepMillis = 790f
private val RevealEasing = CubicBezierEasing(0.20f, 0.72f, 0.24f, 1f)
private val SweepEasing = CubicBezierEasing(0.45f, 0f, 0.30f, 1f)
private val ExitEasing = CubicBezierEasing(0.28f, 0f, 0.24f, 1f)

private data class SplashFrame(
    val elapsed: Float = 0f,
    val exitElapsed: Float = 0f,
)

@Composable
internal fun StartupSplash(destinationReady: Boolean, onFinished: () -> Unit) {
    val ready = rememberUpdatedState(destinationReady)
    val finished = rememberUpdatedState(onFinished)
    val frame = remember { mutableStateOf(SplashFrame()) }
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
                // A stalled first draw should not skip the reveal. The wait limit still uses wall
                // time.
                elapsed += deltaMillis.coerceAtMost(MaxFrameStepMillis) / durationScale
            }
            previousNanos = now

            if (
                exitStart == null &&
                    ((elapsed >= IntroMillis && ready.value) || realElapsed >= MaxWaitMillis)
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
        val reveal = RevealEasing.transform(fraction(elapsed, start = 0f, end = 345f))
        val logoAlpha = RevealEasing.transform(fraction(elapsed, start = 12f, end = 235f))
        val exit = ExitEasing.transform(fraction(exitElapsed, start = 60f, end = ExitMillis))
        drawRect(background, alpha = 1f - exit)

        val logoEdge = 84.dp.toPx()
        val left = center.x - logoEdge / 2f
        val top = center.y - logoEdge / 2f
        scale(lerp(0.955f, 1f, reveal), pivot = center) {
            clipRect(right = left + logoEdge * reveal) {
                translate(left, top) {
                    with(logo) { draw(Size(logoEdge, logoEdge), alpha = logoAlpha * (1f - exit)) }
                }
            }
        }

        val trackWidth = 100.dp.toPx()
        val trackHeight = 2.dp.toPx()
        val trackLeft = center.x - trackWidth / 2f
        val trackTop = top + logoEdge + 25.dp.toPx()
        val radius = CornerRadius(trackHeight / 2f)
        val trackAlpha =
            RevealEasing.transform(fraction(elapsed, start = 112f, end = 280f)) *
                (1f - ExitEasing.transform(fraction(exitElapsed, start = 78f, end = 166f)))
        drawRoundRect(
            ink,
            topLeft = Offset(trackLeft, trackTop),
            size = Size(trackWidth, trackHeight),
            cornerRadius = radius,
            alpha = 0.14f * trackAlpha,
        )

        val sweep = ((elapsed - 135f).coerceAtLeast(0f) % SweepMillis) / SweepMillis
        val leading = SweepEasing.transform(fraction(sweep, start = 0f, end = 0.78f))
        val trailing = SweepEasing.transform(fraction(sweep, start = 0.22f, end = 1f))
        val completion = RevealEasing.transform(fraction(exitElapsed, start = 0f, end = 82f))
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

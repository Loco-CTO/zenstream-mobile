package com.zenstream.zenstreammobile.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zenstream.zenstreammobile.model.SubtitleCue
import com.zenstream.zenstreammobile.model.SubtitleStyle

internal fun subtitleBottomPadding(style: SubtitleStyle) = style.bottomSpacing.dp

@Composable
internal fun SubtitleOverlay(
    cues: List<SubtitleCue>,
    style: SubtitleStyle,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    if (cues.isEmpty()) return
    Column(
        modifier =
            modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        cues.forEach { cue ->
            StyledSubtitleLine(cue.text, style)
        }
    }
}

@Composable
internal fun StyledSubtitleLine(text: String, style: SubtitleStyle) {
    val foreground = parseSubtitleColor(style.fontColor, Color.White)
    val outline = parseSubtitleColor(style.borderColor, Color.Black)
    val background = parseSubtitleColor(style.backgroundColor, Color.Black)
    val textStyle =
        TextStyle(
            fontFamily =
                when (style.fontFamily) {
                    "serif" -> FontFamily.Serif
                    "mono" -> FontFamily.Monospace
                    else -> FontFamily.SansSerif
                },
            fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
            fontSize = (22f * style.textScale / 100f).sp,
            textAlign = TextAlign.Center,
        )
    Box(
        modifier =
            Modifier.background(
                    background.copy(alpha = style.backgroundOpacity / 100f),
                    RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        val outlineWidthDp = subtitleOutlineStrokeWidthDp(style.borderSize)
        if (outlineWidthDp > 0f) {
            Text(
                text = text,
                color = outline,
                style =
                    textStyle.copy(
                        drawStyle =
                            Stroke(
                                width =
                                    with(LocalDensity.current) {
                                        outlineWidthDp.dp.toPx()
                                    }
                            )
                    ),
                modifier = Modifier.clearAndSetSemantics { hideFromAccessibility() },
            )
        }
        Text(text = text, color = foreground, style = textStyle)
    }
}

internal fun subtitleOutlineStrokeWidthDp(borderSize: Float): Float =
    borderSize.coerceIn(0f, 8f) * 2f

private fun parseSubtitleColor(value: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(fallback)

package com.zenstream.zenstreammobile.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R

@Composable
internal fun MiniVideoControls(
    playing: Boolean,
    canControl: Boolean,
    positionSeconds: Double,
    durationSeconds: Double,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onExpand: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .clickable(
                    onClickLabel = stringResource(R.string.player_expand),
                    onClick = onExpand,
                )
        )
        Row(
            Modifier.align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = .55f), Color.Transparent)
                    )
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onToggle, enabled = canControl) {
                Icon(
                    painterResource(
                        if (playing) LucideR.drawable.lucide_ic_pause
                        else LucideR.drawable.lucide_ic_play
                    ),
                    stringResource(if (playing) R.string.pause else R.string.play),
                    tint = Color.White,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    painterResource(LucideR.drawable.lucide_ic_x),
                    stringResource(R.string.player_close),
                    tint = Color.White,
                )
            }
        }
        Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp)) {
            drawRect(Color.White.copy(alpha = .25f))
            val fraction =
                if (durationSeconds > 0)
                    (positionSeconds / durationSeconds).coerceIn(0.0, 1.0).toFloat()
                else 0f
            drawRect(Color(0xFFC4B5FD), size = size.copy(width = size.width * fraction))
        }
    }
}

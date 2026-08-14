package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.zenstream.zenstreammobile.R
import kotlin.math.roundToInt

internal data class RgbColor(val red: Int, val green: Int, val blue: Int) {
    fun clamped(): RgbColor =
        RgbColor(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255))

    fun toHex(): String {
        val color = clamped()
        return "#%02x%02x%02x".format(color.red, color.green, color.blue)
    }

    fun toComposeColor(): Color {
        val color = clamped()
        return Color(color.red / 255f, color.green / 255f, color.blue / 255f)
    }
}

internal data class HsvColor(val hue: Float, val saturation: Float, val value: Float)

internal fun parseHexColor(value: String): RgbColor? {
    if (!Regex("^#[0-9a-fA-F]{6}$").matches(value)) return null
    return RgbColor(
        value.substring(1, 3).toInt(16),
        value.substring(3, 5).toInt(16),
        value.substring(5, 7).toInt(16),
    )
}

internal fun rgbToHsv(color: RgbColor): HsvColor {
    val red = color.red.coerceIn(0, 255) / 255f
    val green = color.green.coerceIn(0, 255) / 255f
    val blue = color.blue.coerceIn(0, 255) / 255f
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    val hue =
        when {
            delta == 0f -> 0f
            max == red -> (60f * ((green - blue) / delta) + 360f) % 360f
            max == green -> 60f * ((blue - red) / delta + 2f)
            else -> 60f * ((red - green) / delta + 4f)
        }
    val saturation = if (max == 0f) 0f else delta / max
    return HsvColor(hue, saturation, max)
}

internal fun hsvToRgb(color: HsvColor): RgbColor {
    val hue = ((color.hue % 360f) + 360f) % 360f
    val saturation = color.saturation.coerceIn(0f, 1f)
    val value = color.value.coerceIn(0f, 1f)
    val chroma = value * saturation
    val x = chroma * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
    val match = value - chroma
    val (red, green, blue) =
        when {
            hue < 60f -> Triple(chroma, x, 0f)
            hue < 120f -> Triple(x, chroma, 0f)
            hue < 180f -> Triple(0f, chroma, x)
            hue < 240f -> Triple(0f, x, chroma)
            hue < 300f -> Triple(x, 0f, chroma)
            else -> Triple(chroma, 0f, x)
        }
    return RgbColor(
        ((red + match) * 255f).roundToInt(),
        ((green + match) * 255f).roundToInt(),
        ((blue + match) * 255f).roundToInt(),
    ).clamped()
}

@Composable
internal fun SubtitleColorField(label: String, value: String, onChange: (String) -> Unit) {
    var pickerVisible by remember { mutableStateOf(false) }
    val color = remember(value) { parseHexColor(value) ?: RgbColor(255, 255, 255) }
    val valueDescription = stringResource(R.string.subtitle_color_current_value, value)

    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
        trailingContent = {
            Box(
                modifier =
                    Modifier.size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.toComposeColor())
                        .semantics { contentDescription = valueDescription }
            )
        },
        modifier =
            Modifier.fillMaxWidth()
                .clickable { pickerVisible = true }
                .semantics {
                    role = Role.Button
                    contentDescription = "$label, $value"
                },
    )

    if (pickerVisible) {
        SubtitleColorPickerDialog(
            label = label,
            initialValue = value,
            onChange = onChange,
            onDismiss = { pickerVisible = false },
        )
    }
}

@Composable
private fun SubtitleColorPickerDialog(
    label: String,
    initialValue: String,
    onChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialColor = remember(initialValue) {
        parseHexColor(initialValue) ?: RgbColor(255, 255, 255)
    }
    var rgb by remember(initialColor) { mutableStateOf(initialColor) }
    var hsv by remember(initialColor) { mutableStateOf(rgbToHsv(initialColor)) }
    val previewDescription = stringResource(R.string.subtitle_color_preview)
    val valueDescription = stringResource(R.string.subtitle_color_value, rgb.toHex())

    fun updateRgb(next: RgbColor) {
        rgb = next.clamped()
        hsv = rgbToHsv(rgb)
        onChange(rgb.toHex())
    }

    fun updateHsv(next: HsvColor) {
        hsv = next.copy(saturation = next.saturation.coerceIn(0f, 1f), value = next.value.coerceIn(0f, 1f))
        rgb = hsvToRgb(hsv)
        onChange(rgb.toHex())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subtitle_color_picker_title, label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SaturationValueField(
                    hue = hsv.hue,
                    saturation = hsv.saturation,
                    value = hsv.value,
                    onChange = { saturation, value ->
                        updateHsv(hsv.copy(saturation = saturation, value = value))
                    },
                )
                HueField(hue = hsv.hue, onChange = { updateHsv(hsv.copy(hue = it)) })
                Box(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(rgb.toComposeColor())
                            .semantics {
                                contentDescription = previewDescription
                            }
                )
                Text(
                    rgb.toHex(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics {
                        contentDescription = valueDescription
                    },
                )
                RgbSlider(
                    label = stringResource(R.string.subtitle_color_red),
                    value = rgb.red,
                    onChange = { updateRgb(rgb.copy(red = it)) },
                )
                RgbSlider(
                    label = stringResource(R.string.subtitle_color_green),
                    value = rgb.green,
                    onChange = { updateRgb(rgb.copy(green = it)) },
                )
                RgbSlider(
                    label = stringResource(R.string.subtitle_color_blue),
                    value = rgb.blue,
                    onChange = { updateRgb(rgb.copy(blue = it)) },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

@Composable
private fun SaturationValueField(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    val description = stringResource(R.string.subtitle_color_saturation_value)
    Canvas(
        modifier =
            Modifier.fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun update(position: Offset) {
                            onChange(
                                (position.x / size.width).coerceIn(0f, 1f),
                                (1f - position.y / size.height).coerceIn(0f, 1f),
                            )
                        }
                        update(down.position)
                        drag(down.id) { change ->
                            change.consume()
                            update(change.position)
                        }
                    }
                }
                .semantics { contentDescription = description },
    ) {
        val hueColor = hsvToRgb(HsvColor(hue, 1f, 1f)).toComposeColor()
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        drawCircle(
            color = Color.White,
            radius = 9.dp.toPx(),
            center = Offset(saturation * size.width, (1f - value) * size.height),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun HueField(hue: Float, onChange: (Float) -> Unit) {
    val description = stringResource(R.string.subtitle_color_hue)
    Canvas(
        modifier =
            Modifier.fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun update(position: Offset) {
                            onChange((position.x / size.width).coerceIn(0f, 1f) * 360f)
                        }
                        update(down.position)
                        drag(down.id) { change ->
                            change.consume()
                            update(change.position)
                        }
                    }
                }
                .semantics { contentDescription = description },
    ) {
        val colors =
            (0..6).map { index -> hsvToRgb(HsvColor(index * 60f, 1f, 1f)).toComposeColor() }
        drawRect(Brush.horizontalGradient(colors))
        drawCircle(
            color = Color.White,
            radius = 9.dp.toPx(),
            center = Offset((hue / 360f) * size.width, size.height / 2f),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun RgbSlider(label: String, value: Int, onChange: (Int) -> Unit) {
    val sliderDescription = stringResource(R.string.subtitle_color_slider, label)
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(value.toString())
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            steps = 254,
            modifier =
                Modifier.semantics {
                    contentDescription = sliderDescription
                },
        )
    }
}

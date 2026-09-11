package com.zenstream.zenstreammobile.ui.components

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import com.vanniktech.blurhash.BlurHash
import com.zenstream.zenstreammobile.model.MediaItem
import kotlin.math.roundToInt

private const val MUSIC_ACCENT_VALUE = .82f

/**
 * The small palette used by music surfaces. It is intentionally derived from the same Primary blur
 * hash that is shown while authenticated artwork is loading, so the artwork, backdrop, controls,
 * and mini-player remain visually related.
 */
data class ArtworkPalette(
    val accent: Color,
    val background: Color,
    val surface: Color,
    val onAccent: Color,
) {
    companion object {
        val fallback =
            ArtworkPalette(
                accent = Color.hsv(255f, .38f, MUSIC_ACCENT_VALUE),
                background = Color(0xFF17131F),
                surface = Color(0xFF282130),
                onAccent = Color.White,
            )
    }
}

fun musicArtworkPalette(item: MediaItem?): ArtworkPalette {
    val blurHash = item?.imageBlurHashes?.get("Primary")?.takeIf(String::isNotBlank)
    val bitmap = blurHash?.let { value ->
        runCatching { BlurHash.decode(value, 24, 24, 1f, false) }.getOrNull()
    }
    return artworkPalette(bitmap)
}

private fun artworkPalette(bitmap: Bitmap?): ArtworkPalette {
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) {
        return ArtworkPalette.fallback
    }

    var red = 0f
    var green = 0f
    var blue = 0f
    var weightTotal = 0f
    val hsv = FloatArray(3)
    val stepX = (bitmap.width / 12).coerceAtLeast(1)
    val stepY = (bitmap.height / 12).coerceAtLeast(1)

    for (y in 0 until bitmap.height step stepY) {
        for (x in 0 until bitmap.width step stepX) {
            val pixel = bitmap.getPixel(x, y)
            val sampleRed = AndroidColor.red(pixel) / 255f
            val sampleGreen = AndroidColor.green(pixel) / 255f
            val sampleBlue = AndroidColor.blue(pixel) / 255f
            AndroidColor.RGBToHSV(
                AndroidColor.red(pixel),
                AndroidColor.green(pixel),
                AndroidColor.blue(pixel),
                hsv,
            )

            val luminance = .2126f * sampleRed + .7152f * sampleGreen + .0722f * sampleBlue
            val edgeWeight = if (luminance < .035f || luminance > .965f) .24f else 1f
            val weight = (.4f + hsv[1] * 1.8f) * edgeWeight
            red += sampleRed * weight
            green += sampleGreen * weight
            blue += sampleBlue * weight
            weightTotal += weight
        }
    }

    if (weightTotal <= 0f) return ArtworkPalette.fallback

    val averageRed = (red / weightTotal).coerceIn(0f, 1f)
    val averageGreen = (green / weightTotal).coerceIn(0f, 1f)
    val averageBlue = (blue / weightTotal).coerceIn(0f, 1f)
    val averagePixel =
        AndroidColor.rgb(
            (averageRed * 255f).roundToInt(),
            (averageGreen * 255f).roundToInt(),
            (averageBlue * 255f).roundToInt(),
        )
    AndroidColor.colorToHSV(averagePixel, hsv)

    val sourceSaturation = hsv[1]
    val sourceValue = hsv[2]
    val accentSaturation = sourceSaturation.coerceIn(.12f, .74f)
    // Keep hue and image-derived saturation, but give every music surface the same visual
    // brightness. Using the source value here made accents from dark covers look muddy and
    // accents from bright covers look harsh.
    val accent = Color.hsv(hsv[0], accentSaturation, MUSIC_ACCENT_VALUE)
    val background =
        Color.hsv(
            hsv[0],
            sourceSaturation.coerceIn(.10f, .54f),
            (sourceValue * .34f).coerceIn(.12f, .25f),
        )
    val surface =
        Color.hsv(
            hsv[0],
            sourceSaturation.coerceIn(.10f, .48f),
            (sourceValue * .48f).coerceIn(.18f, .34f),
        )
    val accentLuminance = .2126f * accent.red + .7152f * accent.green + .0722f * accent.blue

    return ArtworkPalette(
        accent = accent,
        background = background,
        surface = surface,
        onAccent = if (accentLuminance > .58f) Color.Black else Color.White,
    )
}

package com.zenstream.zenstreammobile.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class AvatarImageDimensions(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Avatar source dimensions must be positive" }
    }
}

data class AvatarPan(val x: Float = 0f, val y: Float = 0f)

data class AvatarViewport(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "Avatar viewport dimensions must be positive" }
    }
}

fun rotatedAvatarDimensions(
    source: AvatarImageDimensions,
    rotation: Int,
): AvatarImageDimensions {
    require(rotation in setOf(0, 90, 180, 270)) { "rotation must be a quarter turn" }
    return if (rotation == 90 || rotation == 270) {
        AvatarImageDimensions(source.height, source.width)
    } else source
}

fun avatarCoverScale(
    source: AvatarImageDimensions,
    viewport: AvatarViewport,
    zoom: Float,
    rotation: Int,
): Float {
    require(zoom.isFinite() && zoom >= 1f) { "zoom must be at least one" }
    val rotated = rotatedAvatarDimensions(source, rotation)
    return max(
        viewport.width / rotated.width.toFloat(),
        viewport.height / rotated.height.toFloat(),
    ) * zoom
}

fun clampAvatarZoom(zoom: Float): Float = zoom.coerceIn(1f, 4f)

fun clampAvatarPan(
    source: AvatarImageDimensions,
    viewport: AvatarViewport,
    zoom: Float,
    rotation: Int,
    pan: AvatarPan,
): AvatarPan {
    val scale = avatarCoverScale(source, viewport, clampAvatarZoom(zoom), rotation)
    val rotated = rotatedAvatarDimensions(source, rotation)
    val maxX = max(0f, (rotated.width * scale - viewport.width) / 2f)
    val maxY = max(0f, (rotated.height * scale - viewport.height) / 2f)
    return AvatarPan(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

/**
 * Converts the visible square in the post-rotation image into the server's pixel-space crop. Pan is
 * the displayed image translation: a positive x moves the image right and therefore moves the
 * source crop window left.
 */
fun avatarCropForEditor(
    source: AvatarImageDimensions,
    viewport: AvatarViewport,
    zoom: Float,
    pan: AvatarPan,
    rotation: Int,
): AvatarCrop {
    val safeZoom = clampAvatarZoom(zoom)
    val rotated = rotatedAvatarDimensions(source, rotation)
    val scale = avatarCoverScale(source, viewport, safeZoom, rotation)
    val cropSize =
        min(rotated.width, rotated.height)
            .coerceAtMost((viewport.width / scale).roundToInt())
            .coerceAtLeast(1)
    val safePan = clampAvatarPan(source, viewport, safeZoom, rotation, pan)
    val centerX = (rotated.width - cropSize) / 2f
    val centerY = (rotated.height - cropSize) / 2f
    val cropX = (centerX - safePan.x / scale).roundToInt()
    val cropY = (centerY - safePan.y / scale).roundToInt()
    return AvatarCrop(
        cropX = cropX.coerceIn(0, rotated.width - cropSize),
        cropY = cropY.coerceIn(0, rotated.height - cropSize),
        cropSize = cropSize,
        rotation = rotation,
    )
}

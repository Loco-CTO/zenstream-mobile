package com.zenstream.zenstreammobile.data

import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarCropMathTest {
    private val source = AvatarImageDimensions(1200, 800)
    private val viewport = AvatarViewport(500, 500)

    @Test
    fun cropCoordinatesStayInThePostRotationImageForEveryQuarterTurn() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val rotated = rotatedAvatarDimensions(source, rotation)
            val crop = avatarCropForEditor(source, viewport, 1f, AvatarPan(), rotation)
            assertTrue(crop.cropX >= 0)
            assertTrue(crop.cropY >= 0)
            assertTrue(crop.cropX + crop.cropSize <= rotated.width)
            assertTrue(crop.cropY + crop.cropSize <= rotated.height)
            assertEquals(rotation, crop.rotation)
        }
    }

    @Test
    fun editorUsesCoverSizedImageAtMinimumZoomForEveryQuarterTurn() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val rotated = rotatedAvatarDimensions(source, rotation)
            val scale = avatarCoverScale(source, viewport, 1f, rotation)
            val renderedWidth = rotated.width * scale
            val renderedHeight = rotated.height * scale

            assertEquals(viewport.width.toFloat(), min(renderedWidth, renderedHeight), 0.001f)
            assertTrue(max(renderedWidth, renderedHeight) >= viewport.width)
        }
    }

    @Test
    fun zoomAndPanAreClampedToTheVisibleImage() {
        assertEquals(1f, clampAvatarZoom(-2f), 0f)
        assertEquals(4f, clampAvatarZoom(9f), 0f)
        val clamped = clampAvatarPan(source, viewport, 1f, 0, AvatarPan(10_000f, -10_000f))
        assertTrue(clamped.x >= 0f)
        assertTrue(clamped.y <= 0f)
        val zoomed = clampAvatarPan(source, viewport, 4f, 0, AvatarPan(10_000f, -10_000f))
        assertTrue(zoomed.x > clamped.x)
        assertTrue(zoomed.y < clamped.y)
    }

    @Test
    fun panBoundsMatchTheCoverSizedImageAfterRotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val rotated = rotatedAvatarDimensions(source, rotation)
            val scale = avatarCoverScale(source, viewport, 1f, rotation)
            val expectedMaxX = max(0f, (rotated.width * scale - viewport.width) / 2f)
            val expectedMaxY = max(0f, (rotated.height * scale - viewport.height) / 2f)
            val clamped =
                clampAvatarPan(source, viewport, 1f, rotation, AvatarPan(10_000f, -10_000f))

            assertEquals(expectedMaxX, clamped.x, 0.001f)
            assertEquals(-expectedMaxY, clamped.y, 0.001f)
        }
    }

    @Test
    fun cropCoordinatesStayValidAfterMaximumZoomAndPan() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val rotated = rotatedAvatarDimensions(source, rotation)
            val crop =
                avatarCropForEditor(source, viewport, 4f, AvatarPan(10_000f, -10_000f), rotation)

            assertTrue(crop.cropX >= 0)
            assertTrue(crop.cropY >= 0)
            assertTrue(crop.cropX + crop.cropSize <= rotated.width)
            assertTrue(crop.cropY + crop.cropSize <= rotated.height)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSourceDimensionsAreRejected() {
        AvatarImageDimensions(0, 100)
    }
}

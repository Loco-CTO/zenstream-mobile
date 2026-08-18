package com.zenstream.zenstreammobile.data

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

    @Test(expected = IllegalArgumentException::class)
    fun invalidSourceDimensionsAreRejected() {
        AvatarImageDimensions(0, 100)
    }
}

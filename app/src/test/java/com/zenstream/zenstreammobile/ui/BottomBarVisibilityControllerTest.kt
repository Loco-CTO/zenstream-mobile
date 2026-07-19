package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.ui.navigation.ScrollVisibilityController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollVisibilityControllerTest {
    @Test
    fun hideThresholdIsNotCrossedPrematurely() {
        val controller = controller()

        assertTrue(controller.onScroll(-55f))
    }

    @Test
    fun hideOccursAtThreshold() {
        val controller = controller()

        assertFalse(controller.onScroll(-56f))
    }

    @Test
    fun revealRequiresFullReverseDistance() {
        val controller = controller()
        controller.onScroll(-56f)

        assertFalse(controller.onScroll(63f))
        assertTrue(controller.onScroll(1f))
    }

    @Test
    fun changingDirectionResetsAccumulatedDistance() {
        val controller = controller()

        assertTrue(controller.onScroll(-40f))
        assertTrue(controller.onScroll(32f))
        assertTrue(controller.onScroll(-40f))
        assertFalse(controller.onScroll(-16f))
    }

    @Test
    fun reachingTopRestoresVisibility() {
        val controller = controller()
        controller.onScroll(-56f)

        assertTrue(controller.onScroll(0f, atTop = true))
    }

    private fun controller() = ScrollVisibilityController(
        hideDistance = 56f,
        revealDistance = 64f
    )
}

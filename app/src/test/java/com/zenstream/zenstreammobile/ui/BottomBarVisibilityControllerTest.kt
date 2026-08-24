package com.zenstream.zenstreammobile.ui

import com.zenstream.zenstreammobile.ui.navigation.ScrollVisibilityController
import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollVisibilityControllerTest {
    @Test
    fun upwardScrollProgressivelyHidesChrome() {
        val controller = controller()

        assertEquals(0.5f, controller.onScroll(-28f), EPSILON)
        assertEquals(0.25f, controller.onScroll(-14f), EPSILON)
    }

    @Test
    fun visibilityClampsAtFullyHiddenAndFullyShown() {
        val controller = controller()

        assertEquals(0f, controller.onScroll(-100f), EPSILON)
        assertEquals(1f, controller.onScroll(100f), EPSILON)
    }

    @Test
    fun reverseScrollRevealsProportionally() {
        val controller = controller()
        controller.onScroll(-56f)

        assertEquals(0.5f, controller.onScroll(32f), EPSILON)
        assertEquals(1f, controller.onScroll(32f), EPSILON)
    }

    @Test
    fun changingDirectionKeepsTheCurrentPartialFraction() {
        val controller = controller()

        assertEquals(0.5f, controller.onScroll(-28f), EPSILON)
        assertEquals(0.75f, controller.onScroll(16f), EPSILON)
        assertEquals(0.5714286f, controller.onScroll(-10f), EPSILON)
    }

    @Test
    fun upwardDragWithNoScrollRangeCollapsesChrome() {
        val controller = controller()

        assertEquals(
            0.5f,
            controller.onNestedScroll(
                consumedY = 0f,
                availableY = -28f,
                isScrollable = false,
            ),
            EPSILON,
        )
    }

    @Test
    fun downwardDragAtTopRevealsChromeProportionally() {
        val controller = controller()
        controller.onScroll(-56f)

        assertEquals(
            0.5f,
            controller.onNestedScroll(
                consumedY = 0f,
                availableY = 32f,
                isScrollable = true,
            ),
            EPSILON,
        )
    }

    @Test
    fun consumedScrollStillUpdatesChrome() {
        val controller = controller()

        assertEquals(
            0f,
            controller.onNestedScroll(
                consumedY = -56f,
                availableY = 0f,
                isScrollable = true,
            ),
            EPSILON,
        )
    }

    @Test
    fun nonScrollableRemeasureDoesNotResetPartialState() {
        val controller = controller()
        controller.onScroll(-28f)

        assertEquals(
            0.5f,
            controller.onNestedScroll(
                consumedY = 0f,
                availableY = 0f,
                isScrollable = false,
            ),
            EPSILON,
        )
    }

    @Test
    fun routeResetRestoresFullVisibility() {
        val controller = controller()
        controller.onScroll(-28f)

        assertEquals(1f, controller.resetForRoute(), EPSILON)
    }

    private fun controller() =
        ScrollVisibilityController(
            hideDistance = 56f,
            revealDistance = 64f,
        )

    private companion object {
        const val EPSILON = 0.0001f
    }
}

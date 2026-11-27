package com.zenstream.zenstreammobile.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationLogicTest {
    @Test
    fun myPageKeepsTheMainBottomBarVisible() {
        assertTrue(shouldKeepMainBottomBarVisible("my-page"))
        assertFalse(shouldKeepMainBottomBarVisible("home"))
    }

    @Test
    fun leavingMyPageRequestsAnInPageNavigationReset() {
        assertTrue(shouldResetMyPageNavigationOnRouteChange("my-page", "home"))
        assertFalse(shouldResetMyPageNavigationOnRouteChange("home", "my-page"))
        assertFalse(shouldResetMyPageNavigationOnRouteChange("my-page", "my-page"))
    }

    @Test
    fun selectingMyPageAgainRequestsAnInPageNavigationReset() {
        assertTrue(shouldResetMyPageNavigationOnReselection("my-page", "my-page"))
        assertFalse(shouldResetMyPageNavigationOnReselection("home", "my-page"))
        assertFalse(shouldResetMyPageNavigationOnReselection("my-page", "home"))
    }

    @Test
    fun myPageSwitchesImmediatelyToAndFromInsetChangingTabs() {
        assertTrue(shouldUseImmediateMyPageTabTransition("favorites", "my-page"))
        assertTrue(shouldUseImmediateMyPageTabTransition("library", "my-page"))
        assertTrue(shouldUseImmediateMyPageTabTransition("my-page", "favorites"))
        assertTrue(shouldUseImmediateMyPageTabTransition("my-page", "library"))
        assertFalse(shouldUseImmediateMyPageTabTransition("home", "my-page"))
        assertFalse(shouldUseImmediateMyPageTabTransition("my-page", "home"))
        assertFalse(shouldUseImmediateMyPageTabTransition("favorites", "library"))
    }
}

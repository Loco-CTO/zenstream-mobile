package com.zenstream.zenstreammobile.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshableTest {
    @Test
    fun pullToRefreshIsHiddenDuringAnEmptyInitialLoad() {
        assertFalse(shouldShowPullToRefresh(isLoading = true, hasContent = false))
    }

    @Test
    fun pullToRefreshRemainsVisibleWhenRefreshingExistingContent() {
        assertTrue(shouldShowPullToRefresh(isLoading = true, hasContent = true))
    }

    @Test
    fun detailSeasonLoadingUsesTheInlineEpisodeIndicator() {
        assertFalse(
            shouldShowDetailRefresh(
                isLoading = true,
                seasonLoading = true,
                hasData = true,
            ),
        )
        assertTrue(
            shouldShowDetailRefresh(
                isLoading = true,
                seasonLoading = false,
                hasData = true,
            ),
        )
    }
}

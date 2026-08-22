package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier

internal fun shouldShowPullToRefresh(isLoading: Boolean, hasContent: Boolean): Boolean =
    isLoading && hasContent

internal fun shouldShowDetailRefresh(
    isLoading: Boolean,
    seasonLoading: Boolean,
    hasData: Boolean,
): Boolean = isLoading && !seasonLoading && hasData

@Composable
internal fun ObserveScrollability(
    canScroll: () -> Boolean,
    onScrollabilityChanged: (Boolean) -> Unit,
) {
    val currentCallback = rememberUpdatedState(onScrollabilityChanged)
    LaunchedEffect(Unit) {
        snapshotFlow { canScroll() }.collect { currentCallback.value(it) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PullToRefreshLayout(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
        content = content,
    )
}

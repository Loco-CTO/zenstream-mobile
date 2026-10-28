package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.model.homeSectionId
import com.zenstream.zenstreammobile.model.orderHomeRows
import com.zenstream.zenstreammobile.ui.HomeLayoutUiState
import com.zenstream.zenstreammobile.ui.components.homeSectionTitle
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MyPageHomeLayoutContent(
    state: HomeLayoutUiState,
    onRetry: () -> Unit,
    onVisibilityChange: (String, Boolean) -> Unit,
    onReorder: (List<String>, List<String>) -> Unit,
    onScrollabilityChanged: (Boolean) -> Unit,
) {
    val rows = orderHomeRows(state.rows, state.preferences, includeHidden = true)
    val ids = rows.map(::homeSectionId)
    val listState = rememberLazyListState()
    ObserveScrollability(
        canScroll = { listState.canScrollForward || listState.canScrollBackward },
        onScrollabilityChanged = onScrollabilityChanged,
    )
    val density = androidx.compose.ui.platform.LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val rowHeights = remember { mutableStateMapOf<String, Float>() }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var draggedFrom by remember { mutableIntStateOf(-1) }
    var insertionIndex by remember { mutableIntStateOf(-1) }
    var draggedCenterY by remember { mutableFloatStateOf(0f) }
    var draggedStartCenterY by remember { mutableFloatStateOf(0f) }
    var draggedHeightPx by remember { mutableFloatStateOf(0f) }
    var viewportHeightPx by remember { mutableFloatStateOf(0f) }
    val dragHandleWidthPx = with(density) { 48.dp.toPx() }
    val edgeThresholdPx = with(density) { 64.dp.toPx() }

    fun updateInsertionIndex() {
        val nearest =
            listState.layoutInfo.visibleItemsInfo
                .filter { item -> item.key is String && item.key != draggedId && item.key in ids }
                .minByOrNull { item -> abs(draggedCenterY - (item.offset + item.size / 2f)) }
                ?: return
        val hitIndex = ids.indexOf(nearest.key as String)
        if (hitIndex < 0) return
        val insertion = hitIndex + if (draggedCenterY > nearest.offset + nearest.size / 2f) 1 else 0
        insertionIndex =
            (if (insertion > draggedFrom) insertion - 1 else insertion).coerceIn(0, ids.lastIndex)
    }

    fun finishDrag(commit: Boolean) {
        val from = draggedFrom
        val target = insertionIndex
        draggedId = null
        draggedFrom = -1
        insertionIndex = -1
        draggedCenterY = 0f
        draggedStartCenterY = 0f
        draggedHeightPx = 0f
        if (!commit || from !in ids.indices || target !in ids.indices || target == from) return
        val reordered = ids.toMutableList()
        val moving = reordered.removeAt(from)
        reordered.add(target, moving)
        onReorder(ids, reordered)
    }

    LaunchedEffect(draggedId) {
        while (draggedId != null) {
            val edgeDistance =
                when {
                    draggedCenterY > viewportHeightPx - edgeThresholdPx &&
                        listState.canScrollForward ->
                        draggedCenterY - (viewportHeightPx - edgeThresholdPx)
                    draggedCenterY < edgeThresholdPx && listState.canScrollBackward ->
                        draggedCenterY - edgeThresholdPx
                    else -> 0f
                }
            if (edgeDistance != 0f && viewportHeightPx > 0f) {
                val speed = 4f + 12f * (abs(edgeDistance) / edgeThresholdPx).coerceIn(0f, 1f)
                val consumed = listState.scrollBy(if (edgeDistance > 0) speed else -speed)
                draggedStartCenterY -= consumed
                updateInsertionIndex()
            }
            delay(16)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.home_sections_description),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (state.loading && rows.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.loading_home_sections))
            }
        } else if (state.error && rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_sections_load_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
            }
        } else if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.no_home_sections),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            val listHeight = minOf(560.dp, 72.dp * rows.size + 8.dp * (rows.size - 1))
            LazyColumn(
                state = listState,
                modifier =
                    Modifier.fillMaxWidth()
                        .height(listHeight)
                        .onSizeChanged { viewportHeightPx = it.height.toFloat() }
                        .pointerInput(ids) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { touch ->
                                    if (touch.x > dragHandleWidthPx || ids.size < 2)
                                        return@detectDragGesturesAfterLongPress
                                    val item =
                                        listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                            touch.y >= it.offset &&
                                                touch.y < it.offset + it.size &&
                                                it.key is String &&
                                                it.key in ids
                                        } ?: return@detectDragGesturesAfterLongPress
                                    val id = item.key as String
                                    draggedId = id
                                    draggedFrom = ids.indexOf(id)
                                    insertionIndex = draggedFrom
                                    draggedCenterY = item.offset + item.size / 2f
                                    draggedStartCenterY = draggedCenterY
                                    draggedHeightPx = item.size.toFloat()
                                },
                                onDragCancel = { finishDrag(commit = false) },
                                onDragEnd = { finishDrag(commit = true) },
                                onDrag = { change, dragAmount ->
                                    if (draggedId != null) {
                                        change.consume()
                                        draggedCenterY += dragAmount.y
                                        updateInsertionIndex()
                                    }
                                },
                            )
                        },
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
            ) {
                itemsIndexed(rows, key = { _, row -> homeSectionId(row) }) { index, row ->
                    val id = homeSectionId(row)
                    val isDragged = draggedId == id
                    val step = (rowHeights[id] ?: draggedHeightPx) + with(density) { 8.dp.toPx() }
                    val targetOffset =
                        when {
                            draggedFrom >= 0 &&
                                insertionIndex > draggedFrom &&
                                index in (draggedFrom + 1)..insertionIndex -> -step
                            draggedFrom >= 0 &&
                                insertionIndex in 0 until draggedFrom &&
                                index in insertionIndex until draggedFrom -> step
                            else -> 0f
                        }
                    val neighborOffset by
                        androidx.compose.animation.core.animateFloatAsState(
                            targetValue = targetOffset,
                            animationSpec =
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            label = "home section reorder offset",
                        )
                    val lift by
                        animateFloatAsState(
                            targetValue = if (isDragged) 1f else 0f,
                            animationSpec =
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            label = "home section drag lift",
                        )
                    val elevation by
                        animateDpAsState(
                            targetValue = if (isDragged) 10.dp else 0.dp,
                            animationSpec =
                                spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            label = "home section drag elevation",
                        )
                    val rowDragOffset =
                        if (isDragged) draggedCenterY - draggedStartCenterY else neighborOffset
                    Surface(
                        modifier =
                            Modifier.fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .offset { IntOffset(0, rowDragOffset.roundToInt()) }
                                .zIndex(if (isDragged) 1f else 0f)
                                .shadow(elevation, RoundedCornerShape(14.dp))
                                .graphicsLayer {
                                    val scale = 1f + lift * .02f
                                    scaleX = scale
                                    scaleY = scale
                                }
                                .onSizeChanged { rowHeights[id] = it.height.toFloat() },
                        shape = RoundedCornerShape(14.dp),
                        color =
                            MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = if (isDragged) .98f else .56f
                            ),
                    ) {
                        Row(
                            modifier =
                                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter =
                                    painterResource(LucideR.drawable.lucide_ic_ellipsis_vertical),
                                contentDescription =
                                    stringResource(
                                        R.string.reorder_home_section,
                                        homeSectionTitle(row),
                                    ),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(40.dp).size(20.dp),
                            )
                            Text(
                                text = homeSectionTitle(row),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            val showDescription =
                                stringResource(R.string.show_home_section, homeSectionTitle(row))
                            Switch(
                                checked = id !in state.preferences.hidden,
                                onCheckedChange = { shown -> onVisibilityChange(id, !shown) },
                                modifier =
                                    Modifier.padding(start = 8.dp).semantics {
                                        contentDescription = showDescription
                                    },
                            )
                        }
                    }
                }
            }
        }
        if (state.saveError) {
            Text(
                text = stringResource(R.string.home_sections_save_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.CatalogRepository
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.NotificationItem
import com.zenstream.zenstreammobile.ui.NotificationsViewModel
import com.zenstream.zenstreammobile.ui.components.BlurHashAsyncImage
import com.zenstream.zenstreammobile.ui.components.authenticatedImageRequest
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    repository: CatalogRepository,
    session: AuthSession,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
) {
    val vm: NotificationsViewModel =
        viewModel(
            key = "notifications-${session.userId}-${session.token}",
            factory = NotificationsViewModel.Factory(repository, session),
        )
    val state by vm.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.notifications)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(LucideR.drawable.lucide_ic_arrow_left),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    if (state.unreadCount > 0) {
                        TextButton(onClick = vm::markAllRead) {
                            Text(stringResource(R.string.notifications_mark_all_read))
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.loading && state.items.isEmpty() -> NotificationCenterLoading(padding)
            state.error && state.items.isEmpty() ->
                NotificationErrorState(padding, R.string.notifications_load_failed, vm::refresh)
            state.items.isEmpty() ->
                NotificationEmptyState(
                    stringResource(R.string.notifications_empty),
                    stringResource(R.string.notifications_empty_hint),
                )
            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.items, key = { it.id }) { item ->
                        NotificationRow(
                            item = item,
                            session = session,
                            onClick = {
                                vm.setRead(item, true)
                                (item.seriesId ?: item.itemId)?.let(onOpenItem)
                            },
                            onToggleRead = { vm.setRead(item, item.readAt == null) },
                            onRemove = { vm.remove(item) },
                        )
                    }
                    if (state.nextCursor != null) {
                        item(key = "notifications-load-more") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = vm::loadMore, enabled = !state.loadingMore) {
                                    Text(
                                        stringResource(
                                            if (state.loadingMore) R.string.loading
                                            else R.string.notifications_load_more
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }
}

@Composable
private fun NotificationCenterLoading(padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun NotificationErrorState(
    padding: PaddingValues,
    message: Int,
    onRetry: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(message), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun NotificationEmptyState(title: String, detail: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            detail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
internal fun NotificationRow(
    item: NotificationItem,
    session: AuthSession,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    onRemove: () -> Unit,
) {
    var actionsExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val thumbnailRequest =
        item.thumbnailUrl?.let { authenticatedImageRequest(context, it, session) }
    val readAction =
        if (item.readAt == null) R.string.notifications_mark_read
        else R.string.notifications_mark_unread
    val readActionIcon =
        if (item.readAt == null) LucideR.drawable.lucide_ic_mail_open
        else LucideR.drawable.lucide_ic_mail
    val background =
        if (item.readAt == null) MaterialTheme.colorScheme.primary.copy(alpha = .10f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(background, MaterialTheme.shapes.medium)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(width = 104.dp, height = 60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f))
        ) {
            BlurHashAsyncImage(
                model = thumbnailRequest,
                imageKey = item.thumbnailUrl,
                blurHash = item.thumbnailBlurHash,
                contentDescription = null,
                contentScale = ContentScale.Crop,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.subtitle
                ?.takeIf { it.isNotBlank() }
                ?.let { subtitle ->
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            Text(
                formatNotificationDateTime(item.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f),
                modifier = Modifier.padding(top = 5.dp),
            )
        }
        Box(Modifier.padding(start = 8.dp).size(48.dp)) {
            IconButton(onClick = { actionsExpanded = true }) {
                Icon(
                    painter = painterResource(LucideR.drawable.lucide_ic_ellipsis_vertical),
                    contentDescription = stringResource(R.string.notifications_actions),
                )
            }
            if (item.readAt == null) {
                Box(
                    Modifier.align(Alignment.TopStart)
                        .padding(start = 2.dp, top = 8.dp)
                        .size(8.dp)
                        .background(
                            MaterialTheme.colorScheme.primary,
                            androidx.compose.foundation.shape.CircleShape,
                        )
                )
            }
            DropdownMenu(
                expanded = actionsExpanded,
                onDismissRequest = { actionsExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(readAction)) },
                    onClick = {
                        actionsExpanded = false
                        onToggleRead()
                    },
                    leadingIcon = {
                        Icon(painter = painterResource(readActionIcon), contentDescription = null)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.notifications_remove)) },
                    onClick = {
                        actionsExpanded = false
                        onRemove()
                    },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(LucideR.drawable.lucide_ic_trash_2),
                            contentDescription = null,
                        )
                    },
                )
            }
        }
    }
}

internal fun formatNotificationDateTime(value: String): String {
    val instant =
        runCatching {
                OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
            }
            .getOrNull() ?: return value
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(instant)
}

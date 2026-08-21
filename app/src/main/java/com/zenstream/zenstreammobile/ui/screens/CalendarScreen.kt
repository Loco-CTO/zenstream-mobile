package com.zenstream.zenstreammobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.composables.icons.lucide.R as LucideR
import com.zenstream.zenstreammobile.R
import com.zenstream.zenstreammobile.data.calendarBounds
import com.zenstream.zenstreammobile.data.calendarEpisodePosition
import com.zenstream.zenstreammobile.data.calendarEventDate
import com.zenstream.zenstreammobile.data.calendarEventSortKey
import com.zenstream.zenstreammobile.data.parseCalendarInstant
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.CalendarEvent
import com.zenstream.zenstreammobile.ui.CalendarUiState
import com.zenstream.zenstreammobile.ui.CalendarViewModel
import com.zenstream.zenstreammobile.data.CatalogRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs

private val calendarEventColors =
    listOf(
        Color(0xFF6D5DFC),
        Color(0xFF1AA7A1),
        Color(0xFFD17B35),
        Color(0xFFB14E9B),
        Color(0xFF3B82B6),
        Color(0xFF8E7D36),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarScreen(
    repository: CatalogRepository,
    session: AuthSession,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
) {
    val viewModel: CalendarViewModel =
        viewModel(
            key = "calendar-${session.userId}-${session.token}",
            factory = CalendarViewModel.Factory(repository, session),
        )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val locale = LocalLocale.current.platformLocale
    val zone = remember { ZoneId.systemDefault() }

    Column(modifier = modifier.fillMaxSize()) {
        MyPageSectionHeader(
            title = stringResource(R.string.calendar),
            onBack = onBack,
        )
        CalendarContent(
            state = state,
            modifier = Modifier.weight(1f),
            locale = locale,
            zone = zone,
            onRefresh = viewModel::refresh,
            onPreviousWeek = viewModel::previousWeek,
            onNextWeek = viewModel::nextWeek,
            onToday = viewModel::today,
            onSelectDate = viewModel::selectDate,
            onToggleFollowing = viewModel::toggleFollowing,
            onOpenItem = onOpenItem,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarContent(
    state: CalendarUiState,
    modifier: Modifier = Modifier,
    locale: Locale = LocalLocale.current.platformLocale,
    zone: ZoneId = ZoneId.systemDefault(),
    onRefresh: () -> Unit = {},
    onPreviousWeek: () -> Unit = {},
    onNextWeek: () -> Unit = {},
    onToday: () -> Unit = {},
    onSelectDate: (LocalDate) -> Unit = {},
    onToggleFollowing: (CalendarEvent) -> Unit = {},
    onOpenItem: (String) -> Unit = {},
) {
    var selectedEventId by remember { mutableStateOf<String?>(null) }
    val selectedEvent = state.events.firstOrNull { it.id == selectedEventId }
    val selectedEvents =
        state.events
            .filter { calendarEventDate(it, zone) == state.selectedDate }
            .sortedWith(compareBy<CalendarEvent> { calendarEventSortKey(it) }.thenBy { it.id })
    val bounds = calendarBounds()
    val weekDays = remember(state.weekStart) {
        List(7) { index -> state.weekStart.plusDays(index.toLong()) }
    }

    PullToRefreshLayout(
        isRefreshing = state.loading && state.events.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxSize()) {
            CalendarToolbar(
                weekStart = state.weekStart,
                locale = locale,
                canGoPrevious = state.weekStart > bounds.minimumWeek,
                canGoNext = state.weekStart < bounds.maximumWeek,
                onPreviousWeek = onPreviousWeek,
                onNextWeek = onNextWeek,
                onToday = onToday,
            )
            CalendarWeekStrip(
                days = weekDays,
                selectedDate = state.selectedDate,
                events = state.events,
                locale = locale,
                zone = zone,
                onSelectDate = onSelectDate,
            )
            if (state.loading && state.events.isEmpty()) {
                CalendarLoadingState()
            } else if (state.error && state.events.isEmpty()) {
                CalendarErrorState(onRetry = onRefresh)
            } else {
                if (state.loading) {
                    LinearProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                    )
                }
                if (selectedEvents.isEmpty()) {
                    CalendarEmptyState(date = state.selectedDate, locale = locale)
                } else {
                    CalendarAgenda(
                        date = state.selectedDate,
                        events = selectedEvents,
                        locale = locale,
                        zone = zone,
                        onSelectEvent = { selectedEventId = it.id },
                    )
                }
            }
        }
    }

    selectedEvent?.let { event ->
        CalendarEventSheet(
            event = event,
            locale = locale,
            zone = zone,
            following = event.following,
            followingBusy = state.followingEventId == event.id,
            followError = state.followError,
            onDismiss = { selectedEventId = null },
            onToggleFollowing = { onToggleFollowing(event) },
            onOpenItem = event.catalogItemId?.let { itemId ->
                {
                    selectedEventId = null
                    onOpenItem(itemId)
                }
            },
        )
    }
}

@Composable
private fun CalendarToolbar(
    weekStart: LocalDate,
    locale: Locale,
    canGoPrevious: Boolean,
    canGoNext: Boolean,
    onPreviousWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToday: () -> Unit,
) {
    val weekEnd = weekStart.plusDays(6)
    val monthDayFormatter = DateTimeFormatter.ofPattern("MMM d", locale)
    val previousDescription = stringResource(R.string.calendar_previous)
    val nextDescription = stringResource(R.string.calendar_next)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPreviousWeek,
            enabled = canGoPrevious,
            modifier = Modifier.semantics { contentDescription = previousDescription },
        ) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_chevron_left),
                contentDescription = null,
            )
        }
        IconButton(
            onClick = onNextWeek,
            enabled = canGoNext,
            modifier = Modifier.semantics { contentDescription = nextDescription },
        ) {
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_chevron_right),
                contentDescription = null,
            )
        }
        Text(
            text = "${monthDayFormatter.format(weekStart)} – ${monthDayFormatter.format(weekEnd)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToday) { Text(stringResource(R.string.calendar_today)) }
    }
}

@Composable
private fun CalendarWeekStrip(
    days: List<LocalDate>,
    selectedDate: LocalDate,
    events: List<CalendarEvent>,
    locale: Locale,
    zone: ZoneId,
    onSelectDate: (LocalDate) -> Unit,
) {
    val today = LocalDate.now(zone)
    val weekdayFormatter = DateTimeFormatter.ofPattern("EEE", locale)
    val fullDateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        days.forEach { date ->
            val selected = date == selectedDate
            val isToday = date == today
            val hasEvents = events.any { calendarEventDate(it, zone) == date }
            val dayDescription =
                buildString {
                    append(fullDateFormatter.format(date))
                    if (selected) append(", ${stringResource(R.string.calendar_selected)}")
                    if (hasEvents) append(", ${stringResource(R.string.calendar_has_events)}")
                }
            Surface(
                modifier =
                    Modifier.weight(1f)
                        .heightIn(min = 64.dp)
                        .clickable { onSelectDate(date) }
                        .semantics {
                            role = Role.Button
                            contentDescription = dayDescription
                        },
                color =
                    when {
                        selected -> MaterialTheme.colorScheme.primaryContainer
                        isToday -> MaterialTheme.colorScheme.primary.copy(alpha = .10f)
                        else -> Color.Transparent
                    },
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = weekdayFormatter.format(date).uppercase(locale),
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color =
                            if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarAgenda(
    date: LocalDate,
    events: List<CalendarEvent>,
    locale: Locale,
    zone: ZoneId,
    onSelectEvent: (CalendarEvent) -> Unit,
) {
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = dateFormatter.format(date),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        items(events, key = { it.id }) { event ->
            CalendarEventRow(
                event = event,
                locale = locale,
                zone = zone,
                onClick = { onSelectEvent(event) },
            )
        }
    }
}

@Composable
private fun CalendarEventRow(
    event: CalendarEvent,
    locale: Locale,
    zone: ZoneId,
    onClick: () -> Unit,
) {
    val title =
        calendarEventTitle(
            event,
            movieFallback = stringResource(R.string.calendar_movie),
            episodeFallback = stringResource(R.string.calendar_episode),
        )
    val position = calendarEpisodePosition(event)
    val secondary =
        when {
            event.seriesTitle != null && event.title != null -> event.title
            event.seriesTitle != null -> position
            event.kind == "movie" -> event.releaseType.ifBlank { stringResource(R.string.calendar_movie) }
            else -> position ?: stringResource(R.string.calendar_episode)
        }
    val accent = calendarEventColor(event)
    Surface(
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Button
                    contentDescription = title
                },
        color = if (event.hasFile) accent.copy(alpha = .125f) else Color.Transparent,
        shape = MaterialTheme.shapes.medium,
        border =
            if (event.hasFile) null else BorderStroke(1.dp, accent.copy(alpha = .2f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = event.seriesTitle ?: title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                secondary?.takeIf(String::isNotBlank)?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    text =
                        "${calendarEventTime(event, locale, zone, stringResource(R.string.calendar_all_day))} · ${event.libraryName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .28f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Icon(
                painter = painterResource(LucideR.drawable.lucide_ic_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarEventSheet(
    event: CalendarEvent,
    locale: Locale,
    zone: ZoneId,
    following: Boolean,
    followingBusy: Boolean,
    followError: Boolean,
    onDismiss: () -> Unit,
    onToggleFollowing: () -> Unit,
    onOpenItem: (() -> Unit)?,
) {
    val title =
        calendarEventTitle(
            event,
            movieFallback = stringResource(R.string.calendar_movie),
            episodeFallback = stringResource(R.string.calendar_episode),
        )
    val position = calendarEpisodePosition(event)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (event.seriesTitle != null) "${event.seriesTitle} · $title" else title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text =
                    listOfNotNull(
                            position,
                            calendarEventTime(
                                event,
                                locale,
                                zone,
                                stringResource(R.string.calendar_all_day),
                            ),
                            event.libraryName.takeIf(String::isNotBlank),
                        )
                        .joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CalendarStatusChip(
                    text =
                        stringResource(
                            if (event.state == "existing") R.string.calendar_catalog
                            else R.string.calendar_future
                        )
                )
                if (event.hasFile) {
                    CalendarStatusChip(text = stringResource(R.string.calendar_available))
                }
            }
            if (followError) {
                Text(
                    text = stringResource(R.string.calendar_follow_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (event.followAvailable) {
                    OutlinedButton(
                        onClick = onToggleFollowing,
                        enabled = !followingBusy,
                        modifier = Modifier.weight(1f),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor =
                                    if (following) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                            ),
                    ) {
                        Icon(
                            painter = painterResource(LucideR.drawable.lucide_ic_bookmark),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(if (following) R.string.unfollow else R.string.follow))
                    }
                }
                onOpenItem?.let { open ->
                    Button(onClick = open, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.info))
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarStatusChip(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = .12f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun CalendarLoadingState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CalendarErrorState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.calendar_load_failed),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun CalendarEmptyState(date: LocalDate, locale: Locale) {
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_calendar_days),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f),
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.calendar_empty, dateFormatter.format(date)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun calendarEventTitle(
    event: CalendarEvent,
    movieFallback: String,
    episodeFallback: String,
): String =
    event.title?.takeIf(String::isNotBlank)
        ?: if (event.kind == "movie") movieFallback
        else calendarEpisodePosition(event) ?: episodeFallback

private fun calendarEventColor(event: CalendarEvent): Color {
    var hash = 0
    "${event.libraryId}:${event.seriesTitle ?: event.title ?: event.id}".forEach { character ->
        hash = hash * 31 + character.code
    }
    return calendarEventColors[abs(hash) % calendarEventColors.size]
}

private fun calendarEventTime(
    event: CalendarEvent,
    locale: Locale,
    zone: ZoneId,
    allDayLabel: String,
): String {
    if (event.allDay) return allDayLabel
    val instant = parseCalendarInstant(event.eventAt) ?: return event.eventAt
    return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .withZone(zone)
        .format(instant)
}

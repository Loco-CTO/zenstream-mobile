package com.zenstream.zenstreammobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zenstream.zenstreammobile.data.CalendarDataSource
import com.zenstream.zenstreammobile.data.CatalogException
import com.zenstream.zenstreammobile.data.calendarDayOffset
import com.zenstream.zenstreammobile.data.calendarEventDate
import com.zenstream.zenstreammobile.data.calendarEventSortKey
import com.zenstream.zenstreammobile.data.calendarWeekEndInstant
import com.zenstream.zenstreammobile.data.calendarWeekStartInstant
import com.zenstream.zenstreammobile.data.clampCalendarWeek
import com.zenstream.zenstreammobile.data.moveCalendarWeek
import com.zenstream.zenstreammobile.data.startOfCalendarWeek
import com.zenstream.zenstreammobile.model.AuthSession
import com.zenstream.zenstreammobile.model.CalendarEvent
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CalendarUiState(
    val weekStart: LocalDate = startOfCalendarWeek(LocalDate.now()),
    val selectedDate: LocalDate = LocalDate.now(),
    val events: List<CalendarEvent> = emptyList(),
    val loading: Boolean = true,
    val error: Boolean = false,
    val followingEventId: String? = null,
    val followError: Boolean = false,
)

class CalendarViewModel(
    private val repository: CalendarDataSource,
    private val session: AuthSession,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val todayProvider: () -> LocalDate = { LocalDate.now(zone) },
) : ViewModel() {
    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState = _uiState.asStateFlow()
    private var requestGeneration = 0L
    private var requestJob: Job? = null

    init {
        val today = todayProvider()
        _uiState.value =
            CalendarUiState(
                weekStart = startOfCalendarWeek(today),
                selectedDate = today,
            )
        load()
    }

    fun refresh() {
        load(force = true)
    }

    fun selectDate(date: LocalDate) {
        val current = _uiState.value
        if (date < current.weekStart || date > current.weekStart.plusDays(6)) return
        _uiState.update { it.copy(selectedDate = date, followError = false) }
    }

    fun previousWeek() = moveWeek(-1)

    fun nextWeek() = moveWeek(1)

    fun today() {
        val today = todayProvider()
        val week = clampCalendarWeek(today, today)
        val current = _uiState.value
        if (current.weekStart == week && current.selectedDate == today) {
            refresh()
            return
        }
        _uiState.update {
            it.copy(
                weekStart = week,
                selectedDate = today,
                followError = false,
            )
        }
        load(force = true)
    }

    fun toggleFollowing(event: CalendarEvent) {
        val current = _uiState.value
        if (!event.followAvailable || current.followingEventId != null) return
        val target = !event.following
        _uiState.update {
            it.copy(
                events =
                    it.events.map { value ->
                        if (value.id == event.id) value.copy(following = target) else value
                    },
                followingEventId = event.id,
                followError = false,
            )
        }
        viewModelScope.launch {
            runCatching {
                    repository.setCalendarFollowing(session, event.id, target)
                }
                .onSuccess { following ->
                    _uiState.update {
                        it.copy(
                            events =
                                it.events.map { value ->
                                    if (value.id == event.id) value.copy(following = following)
                                    else value
                                },
                            followingEventId = null,
                        )
                    }
                }
                .onFailure { error ->
                    if (error is CatalogException && error.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                    _uiState.update {
                        it.copy(
                            events =
                                it.events.map { value ->
                                    if (value.id == event.id)
                                        value.copy(following = event.following)
                                    else value
                                },
                            followingEventId = null,
                            followError = true,
                        )
                    }
                }
        }
    }

    fun eventsFor(date: LocalDate = _uiState.value.selectedDate): List<CalendarEvent> =
        _uiState.value.events
            .filter { calendarEventDate(it, zone) == date }
            .sortedWith(compareBy<CalendarEvent> { calendarEventSortKey(it) }.thenBy { it.id })

    private fun moveWeek(amount: Long) {
        val current = _uiState.value
        val nextWeek = moveCalendarWeek(current.weekStart, amount, todayProvider())
        if (nextWeek == current.weekStart) return
        val selectedOffset = calendarDayOffset(current.weekStart, current.selectedDate)
        _uiState.update {
            it.copy(
                weekStart = nextWeek,
                selectedDate = nextWeek.plusDays(selectedOffset),
                followError = false,
            )
        }
        load(force = true)
    }

    private fun load(force: Boolean = false) {
        if (!force && requestJob?.isActive == true) return
        requestJob?.cancel()
        val generation = ++requestGeneration
        val weekStart = _uiState.value.weekStart
        _uiState.update { it.copy(loading = true, error = false) }
        requestJob = viewModelScope.launch {
            runCatching {
                    repository.calendar(
                        session,
                        calendarWeekStartInstant(weekStart, zone),
                        calendarWeekEndInstant(weekStart, zone),
                    )
                }
                .onSuccess { response ->
                    if (generation != requestGeneration) return@onSuccess
                    _uiState.update {
                        it.copy(
                            events = response.events.distinctBy(CalendarEvent::id),
                            loading = false,
                            error = false,
                        )
                    }
                }
                .onFailure { error ->
                    if (generation != requestGeneration) return@onFailure
                    if (error is CatalogException && error.statusCode == 401) {
                        repository.clearSessionIfCurrent(session)
                    }
                    _uiState.update { it.copy(loading = false, error = true) }
                }
        }
    }

    class Factory(
        private val repository: CalendarDataSource,
        private val session: AuthSession,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CalendarViewModel(repository, session) as T
    }
}

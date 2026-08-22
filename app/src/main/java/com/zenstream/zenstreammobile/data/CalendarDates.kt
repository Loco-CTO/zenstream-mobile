package com.zenstream.zenstreammobile.data

import com.zenstream.zenstreammobile.model.CalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal const val CALENDAR_MIN_WEEK_OFFSET = -1L
internal const val CALENDAR_MAX_WEEK_OFFSET = 16L

internal data class CalendarBounds(
    val minimumWeek: LocalDate,
    val maximumWeek: LocalDate,
)

internal fun startOfCalendarWeek(date: LocalDate): LocalDate {
    val daysFromSunday = date.dayOfWeek.value % 7
    return date.minusDays(daysFromSunday.toLong())
}

internal fun calendarBounds(today: LocalDate = LocalDate.now()): CalendarBounds {
    val currentWeek = startOfCalendarWeek(today)
    return CalendarBounds(
        minimumWeek = currentWeek.plusWeeks(CALENDAR_MIN_WEEK_OFFSET),
        maximumWeek = currentWeek.plusWeeks(CALENDAR_MAX_WEEK_OFFSET),
    )
}

internal fun clampCalendarWeek(
    candidate: LocalDate,
    today: LocalDate = LocalDate.now(),
): LocalDate {
    val week = startOfCalendarWeek(candidate)
    val bounds = calendarBounds(today)
    return week.coerceIn(bounds.minimumWeek, bounds.maximumWeek)
}

internal fun moveCalendarWeek(
    current: LocalDate,
    amount: Long,
    today: LocalDate = LocalDate.now(),
): LocalDate = clampCalendarWeek(startOfCalendarWeek(current).plusWeeks(amount), today)

internal fun calendarWeekStartInstant(weekStart: LocalDate, zone: ZoneId): Instant =
    ZonedDateTime.of(weekStart, LocalTime.MIDNIGHT, zone).toInstant()

internal fun calendarWeekEndInstant(weekStart: LocalDate, zone: ZoneId): Instant =
    ZonedDateTime.of(weekStart.plusDays(7), LocalTime.MIDNIGHT, zone).toInstant()

internal fun calendarEventDate(event: CalendarEvent, zone: ZoneId): LocalDate? {
    if (event.allDay) {
        return runCatching { LocalDate.parse(event.eventDate) }.getOrNull()
    }
    return parseCalendarInstant(event.eventAt)?.atZone(zone)?.toLocalDate()
        ?: runCatching { LocalDate.parse(event.eventDate) }.getOrNull()
}

internal fun parseCalendarInstant(value: String): Instant? =
    runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toInstant()
            }
            .getOrNull()

internal fun calendarEventSortKey(event: CalendarEvent): Long =
    if (event.allDay) {
        runCatching { LocalDate.parse(event.eventDate).atStartOfDay(ZoneId.of("UTC")).toInstant() }
            .getOrNull()
            ?.toEpochMilli() ?: Long.MIN_VALUE
    } else {
        parseCalendarInstant(event.eventAt)?.toEpochMilli() ?: Long.MAX_VALUE
    }

internal fun calendarDayOffset(weekStart: LocalDate, selectedDate: LocalDate): Long =
    ChronoUnit.DAYS.between(weekStart, selectedDate).coerceIn(0, 6)

internal fun calendarEpisodePosition(event: CalendarEvent): String? {
    if (event.kind != "episode" || event.episodeNumber == null) return null
    val episode = "E${event.episodeNumber.toString().padStart(2, '0')}"
    return event.seasonNumber?.let { "S${it.toString().padStart(2, '0')}$episode" } ?: episode
}

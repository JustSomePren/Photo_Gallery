package com.justsomepren.gallery.data

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * A colored period in the calendar, e.g. a trip. With [autoSend] every photo
 * from these days goes to [account], into the album [album] if it has one.
 */
data class Event(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val color: Int,
    val startDay: Long,
    val endDay: Long,
    val autoSend: Boolean = false,
    val account: String? = null,
    val album: String? = null,
) {
    fun covers(day: Long) = day in startDay..endDay

    fun overlaps(from: Long, to: Long) = startDay <= to && endDay >= from

    val isActive get() = covers(today())

    val source get() = Source.Period(name, startDay, endDay)
}

/** Of the events covering a day, the shortest: a city on a long trip rather than the whole trip. */
fun List<Event>.mostSpecific(day: Long): Event? = filter { it.covers(day) }.minByOrNull { it.endDay - it.startDay }

val EVENT_COLORS = listOf(
    0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
    0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFFD81B60,
).map { it.toInt() }

fun today() = LocalDate.now().toEpochDay()

fun Long.toDate(): LocalDate = LocalDate.ofEpochDay(this)

/** Local calendar day the photo was taken, as epoch day. */
val MediaItem.day: Long
    get() = Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

private val shortDate = DateTimeFormatter.ofPattern("d. M.", Locale("cs"))
private val longDate = DateTimeFormatter.ofPattern("d. M. yyyy", Locale("cs"))

fun formatDay(day: Long): String = day.toDate().format(longDate)

private val weekdayDate = DateTimeFormatter.ofPattern("EEE d. M. yyyy", Locale("cs"))
private val dayInMonth = DateTimeFormatter.ofPattern("EEEE d. M.", Locale("cs"))

/** "Dnes", "Včera" or "pátek 6. 11." for a day header inside its month. */
fun formatDayHeader(day: Long): String = when (day) {
    today() -> "Dnes"
    today() - 1 -> "Včera"
    else -> day.toDate().format(dayInMonth).replaceFirstChar { it.uppercase() }
}

fun formatWeekday(day: Long): String = day.toDate().format(weekdayDate)

fun formatRange(start: Long, end: Long): String = when {
    start == end -> formatDay(start)
    start.toDate().year == end.toDate().year -> "${start.toDate().format(shortDate)} – ${formatDay(end)}"
    else -> "${formatDay(start)} – ${formatDay(end)}"
}

private val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", Locale("cs"))

fun monthTitle(month: YearMonth): String = month.format(monthFormat).replaceFirstChar { it.uppercase() }

val MediaItem.month: YearMonth get() = YearMonth.from(day.toDate())

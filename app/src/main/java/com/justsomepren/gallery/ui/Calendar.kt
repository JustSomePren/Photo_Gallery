package com.justsomepren.gallery.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.justsomepren.gallery.data.CalendarZoom
import com.justsomepren.gallery.data.mostSpecific
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.bestFirst
import com.justsomepren.gallery.data.pairedDays
import com.justsomepren.gallery.data.CalendarItem
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.day
import com.justsomepren.gallery.data.monthTitle
import com.justsomepren.gallery.data.toDate
import com.justsomepren.gallery.data.today
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.min

private val WEEKDAYS = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")

/** One line of the calendar list; [month] tells where it is, to keep the place when zooming. */
private sealed interface CalendarRow {
    val month: YearMonth

    data class Year(val year: Int) : CalendarRow {
        override val month: YearMonth get() = YearMonth.of(year, 12)
    }

    data class Month(override val month: YearMonth) : CalendarRow
    data class Day(val day: Long) : CalendarRow {
        override val month: YearMonth get() = YearMonth.from(day.toDate())
    }
}

/**
 * Photos by date, newest first, at three zoom levels: whole years, months as a calendar, or each
 * day with its photos in a row. Pinch or the chips at the top change the level.
 */
@Composable
fun CalendarView(
    media: List<MediaItem>,
    events: List<Event>,
    calendarItems: List<CalendarItem> = emptyList(),
    zoom: Int,
    state: LazyListState,
    contentPadding: PaddingValues,
    header: @Composable () -> Unit,
    onZoom: (Int) -> Unit,
    onDay: (Long) -> Unit,
    onPhoto: (Long, MediaItem) -> Unit,
    onNewEvent: (Long) -> Unit,
    onEvent: (Event) -> Unit,
) {
    // Only camera photos (no screenshots or downloads), the best of each day first
    val byDay = remember(media) { media.filter { it.inCalendar }.groupBy { it.day }.mapValues { (_, items) -> items.bestFirst() } }
    val paired = remember(byDay, calendarItems) { pairedDays(calendarItems, byDay) }
    val months = remember(byDay, events) { calendarMonths(byDay.keys, events) }
    val rows = remember(months, byDay, zoom) {
        when (zoom) {
            CalendarZoom.YEAR -> months.map { it.year }.distinct().map { CalendarRow.Year(it) }
            CalendarZoom.DAYS -> {
                // Days grouped by month once, not every month checking every day
                val daysByMonth = byDay.keys.groupBy { YearMonth.from(it.toDate()) }
                months.flatMap { month ->
                    listOf(CalendarRow.Month(month)) + daysByMonth[month].orEmpty().sortedDescending().map { CalendarRow.Day(it) }
                }
            }
            else -> months.map { CalendarRow.Month(it) }
        }
    }

    // Zooming keeps the month that was at the top in view.
    var anchor by remember { mutableStateOf<YearMonth?>(null) }
    fun zoomTo(level: Int, at: YearMonth? = null) {
        if (level == zoom || level !in CalendarZoom.YEAR..CalendarZoom.DAYS) return
        anchor = at ?: rows.getOrNull(state.firstVisibleItemIndex - 1)?.month
        onZoom(level)
    }
    LaunchedEffect(rows) {
        val month = anchor ?: return@LaunchedEffect
        anchor = null
        val index = rows.indexOfFirst { if (it is CalendarRow.Year) it.year == month.year else it.month == month }
        if (index >= 0) state.scrollToItem(index + 1)
    }

    LazyColumn(state = state, contentPadding = contentPadding, modifier = Modifier.pinchToZoom { zoomTo(zoom + it) }) {
        item {
            header()
            ZoomChips(zoom) { zoomTo(it) }
        }
        items(rows, key = { it.toString() }) { row ->
            when (row) {
                is CalendarRow.Year -> YearCalendar(row.year, byDay) { zoomTo(CalendarZoom.MONTH, it) }
                is CalendarRow.Month -> {
                    val first = row.month.atDay(1).toEpochDay()
                    val last = row.month.atEndOfMonth().toEpochDay()
                    val inMonth = events.filter { it.overlaps(first, last) }
                    if (zoom == CalendarZoom.DAYS) {
                        MonthTitle(row.month, inMonth, onEvent)
                    } else {
                        MonthCalendar(row.month, byDay, paired, inMonth, onDay, onNewEvent, onEvent)
                    }
                }
                is CalendarRow.Day -> DayPhotos(row.day, byDay[row.day].orEmpty(), events.mostSpecific(row.day), onDay, onPhoto, onNewEvent)
            }
        }
    }
}

@Composable
private fun ZoomChips(zoom: Int, onZoom: (Int) -> Unit) {
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Rok", "Měsíc", "Dny").forEachIndexed { level, name ->
            FilterChip(selected = zoom == level, onClick = { onZoom(level) }, label = { Text(name) })
        }
    }
}

/** A year as twelve small months; a day with photos shows one. Tapping a month zooms into it. */
@Composable
private fun YearCalendar(year: Int, byDay: Map<Long, List<MediaItem>>, onMonth: (YearMonth) -> Unit) {
    val thumbPx = gridThumbPx(21)
    Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
        Text("$year", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(4.dp))
        (1..12).chunked(3).forEach { quarter ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quarter.forEach { number ->
                    val month = YearMonth.of(year, number)
                    val offset = month.atDay(1).dayOfWeek.value - 1
                    val first = month.atDay(1).toEpochDay()
                    val count = (first until first + month.lengthOfMonth()).sumOf { byDay[it]?.size ?: 0 }
                    Column(Modifier.weight(1f).clickable { onMonth(month) }.padding(vertical = 4.dp)) {
                        Text(
                            monthTitle(month).substringBefore(' ') + if (count > 0) " · $count" else "",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        (0 until (offset + month.lengthOfMonth() + 6) / 7).forEach { week ->
                            Row {
                                for (column in 0..6) {
                                    val day = first - offset + week * 7 + column
                                    val photo = byDay[day]?.firstOrNull()
                                    Box(Modifier.weight(1f).aspectRatio(1f).padding(0.5.dp)) {
                                        when {
                                            photo != null -> Thumbnail(photo.uri, thumbPx, Modifier.fillMaxSize())
                                            day in first until first + month.lengthOfMonth() -> Box(
                                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthTitle(month: YearMonth, events: List<Event>, onEvent: (Event) -> Unit) {
    FlowRow(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(monthTitle(month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterVertically))
        events.forEach { EventChip(it) { onEvent(it) } }
    }
}

/** One day: its date on the left, its photos in a row to scroll through and open. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayPhotos(
    day: Long,
    items: List<MediaItem>,
    event: Event?,
    onDay: (Long) -> Unit,
    onPhoto: (Long, MediaItem) -> Unit,
    onNewEvent: (Long) -> Unit,
) {
    val thumbPx = gridThumbPx(3)
    val date = day.toDate()
    val weekend = date.dayOfWeek.value >= 6
    Row(Modifier.padding(vertical = 2.dp).height(120.dp)) {
        Column(
            Modifier
                .width(52.dp)
                .fillMaxHeight()
                .combinedClickable(onClick = { onDay(day) }, onLongClick = { onNewEvent(day) })
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                WEEKDAYS[date.dayOfWeek.value - 1],
                style = MaterialTheme.typography.labelMedium,
                color = if (weekend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${date.dayOfMonth}", style = MaterialTheme.typography.titleLarge, fontWeight = if (day == today()) FontWeight.Bold else null)
            Text("${items.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            event?.let { Box(Modifier.padding(top = 4.dp).width(24.dp).height(4.dp).background(Color(it.color), RoundedCornerShape(2.dp))) }
        }
        // No sideways scrolling here: a slightly diagonal swipe would catch the row and stop the list.
        // As many photos as fit; the last tile of a busy day opens the whole day.
        BoxWithConstraints(Modifier.fillMaxHeight()) {
            val fit = (maxWidth / maxHeight).toInt().coerceAtLeast(1)
            val shown = if (items.size > fit) items.take(fit - 1) else items
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                shown.forEach { item ->
                    Box(Modifier.fillMaxHeight().aspectRatio(1f).clickable { onPhoto(day, item) }) {
                        Thumbnail(item.uri, thumbPx, Modifier.fillMaxSize())
                        if (item.cloud) CornerIcon(Icons.Default.Cloud, Alignment.TopEnd)
                    }
                }
                if (items.size > shown.size) {
                    Box(
                        Modifier.fillMaxHeight().aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onDay(day) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+${items.size - shown.size}", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

/** Months with photos or events, plus the current one. */
private fun calendarMonths(days: Set<Long>, events: List<Event>): List<YearMonth> {
    val months = days.map { YearMonth.from(it.toDate()) }.toMutableSet()
    months += YearMonth.now()
    for (event in events) {
        var month = YearMonth.from(event.startDay.toDate())
        val end = YearMonth.from(event.endDay.toDate())
        while (month <= end) {
            months += month
            month = month.plusMonths(1)
        }
    }
    return months.sortedDescending()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthCalendar(
    month: YearMonth,
    byDay: Map<Long, List<MediaItem>>,
    paired: Set<Long>,
    events: List<Event>,
    onDay: (Long) -> Unit,
    onNewEvent: (Long) -> Unit,
    onEvent: (Event) -> Unit,
) {
    val thumbPx = gridThumbPx(7)
    val first = month.atDay(1).toEpochDay()
    val last = month.atEndOfMonth().toEpochDay()
    val offset = month.atDay(1).dayOfWeek.value - 1
    val rows = (offset + month.lengthOfMonth() + 6) / 7
    val today = today()

    Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
        FlowRow(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                monthTitle(month),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            events.forEach { EventChip(it) { onEvent(it) } }
        }
        Row {
            WEEKDAYS.forEachIndexed { index, name ->
                Text(
                    name,
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (index >= 5) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        repeat(rows) { row ->
            val weekStart = first - offset + row * 7
            Box {
                Row {
                    for (column in 0..6) {
                        val day = weekStart + column
                        if (day in first..last) {
                            DayCell(day, byDay[day].orEmpty(), day in paired, column >= 5, day == today, thumbPx, Modifier.weight(1f), onDay, onNewEvent)
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
                EventBars(events, max(weekStart, first), min(weekStart + 6, last), weekStart)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    day: Long,
    items: List<MediaItem>,
    paired: Boolean,
    weekend: Boolean,
    isToday: Boolean,
    thumbPx: Int,
    modifier: Modifier,
    onDay: (Long) -> Unit,
    onNewEvent: (Long) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .clip(shape)
            .background(if (weekend) colors.secondaryContainer else colors.surfaceContainerHigh)
            .then(if (isToday) Modifier.border(2.dp, colors.primary, shape) else Modifier)
            .combinedClickable(onClick = { if (items.isNotEmpty()) onDay(day) }, onLongClick = { onNewEvent(day) }),
    ) {
        if (items.size >= 4) {
            // A busy day shows four of its photos.
            Column(Modifier.fillMaxSize()) {
                items.take(4).chunked(2).forEach { pair ->
                    Row(Modifier.weight(1f)) {
                        pair.forEach { Thumbnail(it.uri, thumbPx / 2, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
        } else {
            items.firstOrNull()?.let { Thumbnail(it.uri, thumbPx, Modifier.fillMaxSize()) }
        }
        val onPhoto = items.isNotEmpty()
        CellLabel("${day.toDate().dayOfMonth}", Alignment.TopStart, onPhoto, bold = isToday)
        if (items.size > 1) CellLabel("${items.size}", Alignment.TopEnd, onPhoto)
        // Some photos belong to an event from the Kalendář app
        if (paired) {
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = 5.dp).size(7.dp)
                    .background(colors.primary, CircleShape).border(1.dp, Color.White, CircleShape),
            )
        }
    }
}

@Composable
private fun BoxScope.CellLabel(text: String, alignment: Alignment, onPhoto: Boolean, bold: Boolean = false) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        color = if (onPhoto) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .align(alignment)
            .padding(2.dp)
            .then(if (onPhoto) Modifier.background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(4.dp)) else Modifier)
            .padding(horizontal = 3.dp),
    )
}

/** Colored bars across the days of a week, like in a calendar app. */
@Composable
private fun BoxScope.EventBars(events: List<Event>, from: Long, to: Long, weekStart: Long) {
    val week = events.filter { it.overlaps(from, to) }.take(2)
    if (week.isEmpty()) return
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 3.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        week.forEach { event ->
            val start = (max(event.startDay, from) - weekStart).toInt()
            val end = (min(event.endDay, to) - weekStart).toInt()
            val color = Color(event.color)
            Row(Modifier.fillMaxWidth()) {
                if (start > 0) Spacer(Modifier.weight(start.toFloat()))
                Box(
                    Modifier
                        .weight((end - start + 1).toFloat())
                        .padding(horizontal = 2.dp)
                        .height(16.dp)
                        .background(color, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        event.name,
                        fontSize = 10.sp,
                        lineHeight = 12.sp,
                        color = if (color.luminance() > 0.6f) Color.Black else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (end < 6) Spacer(Modifier.weight((6 - end).toFloat()))
            }
        }
    }
}

package com.justsomepren.gallery.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.justsomepren.gallery.data.CalendarItem
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.cover
import com.justsomepren.gallery.data.day
import com.justsomepren.gallery.data.monthTitle
import com.justsomepren.gallery.data.pairedDays
import com.justsomepren.gallery.data.toDate
import com.justsomepren.gallery.data.today
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.min

private val WEEKDAYS = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")

/** Months under each other, newest first. A whole month fits on the screen. */
@Composable
fun CalendarView(
    media: List<MediaItem>,
    events: List<Event>,
    calendarItems: List<CalendarItem> = emptyList(),
    state: LazyListState,
    contentPadding: PaddingValues,
    header: @Composable () -> Unit,
    onDay: (Long) -> Unit,
    onNewEvent: (Long) -> Unit,
    onEvent: (Event) -> Unit,
) {
    // Only what was really taken with the camera, no screenshots or downloads
    val byDay = remember(media) { media.filter { it.isCamera }.groupBy { it.day } }
    val months = remember(byDay, events) { calendarMonths(byDay.keys, events) }
    val paired = remember(byDay, calendarItems) { pairedDays(calendarItems, byDay) }
    val thumbPx = gridThumbPx()

    LazyColumn(state = state, contentPadding = contentPadding) {
        item { header() }
        items(months, key = { it.toString() }) { month ->
            val first = month.atDay(1).toEpochDay()
            val last = month.atEndOfMonth().toEpochDay()
            MonthCalendar(
                month = month,
                byDay = byDay,
                paired = paired,
                events = events.filter { it.overlaps(first, last) },
                thumbPx = thumbPx,
                onDay = onDay,
                onNewEvent = onNewEvent,
                onEvent = onEvent,
            )
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
    thumbPx: Int,
    onDay: (Long) -> Unit,
    onNewEvent: (Long) -> Unit,
    onEvent: (Event) -> Unit,
) {
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
        val cover = remember(items) { items.cover() }
        cover?.let { Thumbnail(it.uri, thumbPx, Modifier.fillMaxSize()) }
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

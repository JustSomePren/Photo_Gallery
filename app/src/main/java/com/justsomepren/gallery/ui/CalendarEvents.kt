package com.justsomepren.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.justsomepren.gallery.data.CalendarItem
import com.justsomepren.gallery.data.CalendarLink
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.data.filterBy
import com.justsomepren.gallery.data.formatRange

/** Events from the Kalendář app on this day that have photos; tapping one shows just its photos. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayEvents(day: Long, items: List<CalendarItem>, media: List<MediaItem>, onOpen: (Source) -> Unit) {
    val paired = remember(day, items, media) {
        items.filter { it.covers(day) }
            .map { it to media.filterBy(it.source, emptyList()).size }
            .filter { it.second > 0 }
    }
    if (paired.isEmpty()) return
    FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        paired.forEach { (item, count) ->
            AssistChip(
                onClick = { onOpen(item.source) },
                leadingIcon = { Box(Modifier.size(10.dp).background(Color(item.color), CircleShape)) },
                label = { Text("${item.title} · $count") },
            )
        }
    }
}

/** A vacation from the Kalendář app is changed there, so it stays the same in both apps. */
@Composable
fun LinkedEventDialog(event: Event, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(event.name) },
        text = {
            Column {
                Text(formatRange(event.startDay, event.endDay))
                Text(
                    if (event.autoSend) "Posílá do ${event.account}" + (event.album?.let { " · album $it" } ?: "")
                    else "Fotky se neposílají",
                )
                Hint("Dovolená z Kalendáře. Název, dny, album a účet změníš tam.")
            }
        },
        confirmButton = {
            TextButton(onClick = {
                CalendarLink.openEvent(context, event.linkedId ?: return@TextButton, event.linkedBegin)
                onDismiss()
            }) { Text("Otevřít v Kalendáři") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zavřít") } },
    )
}

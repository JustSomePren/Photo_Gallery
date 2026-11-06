package com.justsomepren.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.EVENT_COLORS
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.formatRange
import com.justsomepren.gallery.data.toDate
import com.justsomepren.gallery.data.today

private const val DAY_MS = 86_400_000L

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventEditor(initial: Event, vm: GalleryViewModel, onDismiss: () -> Unit) {
    val accounts by vm.prefs.accounts.collectAsStateWithLifecycle()
    val exists = remember { vm.prefs.events.value.any { it.id == initial.id } }

    var name by remember { mutableStateOf(initial.name) }
    var color by remember { mutableIntStateOf(initial.color) }
    var start by remember { mutableLongStateOf(initial.startDay) }
    var end by remember { mutableLongStateOf(initial.endDay) }
    var autoSend by remember { mutableStateOf(initial.autoSend) }
    var account by remember { mutableStateOf(initial.account ?: accounts.firstOrNull()) }
    var album by remember { mutableStateOf(initial.album ?: initial.name) }
    // The album is named like the event until the user types their own name.
    var albumFollowsName by remember { mutableStateOf(initial.album == null || initial.album == initial.name) }
    var pickingDates by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (exists) "Upravit událost" else "Nová událost") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        if (albumFollowsName) album = it
                    },
                    label = { Text("Název") },
                    placeholder = { Text("např. Dovča Praha") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EVENT_COLORS.forEach { option ->
                        Box(
                            Modifier
                                .size(26.dp)
                                .background(Color(option), CircleShape)
                                .border(if (option == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { color = option },
                        )
                    }
                }
                OutlinedButton(onClick = { pickingDates = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.DateRange, null, Modifier.padding(end = 8.dp))
                    Text(formatRange(start, end))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssistChip(onClick = { start = today(); end = today() }, label = { Text("Jen dnes") })
                    AssistChip(onClick = { end = start + 6 }, label = { Text("Týden") })
                    AssistChip(onClick = {
                        val date = start.toDate()
                        end = date.withDayOfMonth(date.lengthOfMonth()).toEpochDay()
                    }, label = { Text("Do konce měsíce") })
                }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Automaticky posílat fotky z těchto dní", Modifier.weight(1f))
                    Switch(autoSend, onCheckedChange = { autoSend = it })
                }
                if (autoSend) {
                    if (accounts.isEmpty()) {
                        Text("Nejdřív přidej Google účet v záložce Odesílání.", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text("Účet", style = MaterialTheme.typography.labelMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            accounts.forEach {
                                FilterChip(selected = account == it, onClick = { account = it }, label = { Text(it) })
                            }
                        }
                    }
                    OutlinedTextField(
                        value = album,
                        onValueChange = {
                            album = it
                            albumFollowsName = false
                        },
                        label = { Text("Do alba (nepovinné)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint("Odešle se samo, jakmile bude Wi-Fi. Pod nastavenou hranicí baterie jen při nabíjení.")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !autoSend || account != null,
                onClick = {
                    vm.saveEvent(
                        Event(
                            id = initial.id,
                            name = name.trim().ifBlank { "Událost" },
                            color = color,
                            startDay = minOf(start, end),
                            endDay = maxOf(start, end),
                            autoSend = autoSend,
                            account = account.takeIf { autoSend },
                            album = album.trim().ifBlank { null }.takeIf { autoSend },
                        ),
                    )
                    onDismiss()
                },
            ) { Text("Uložit") }
        },
        dismissButton = {
            Row {
                if (exists) {
                    TextButton(onClick = {
                        vm.deleteEvent(initial)
                        onDismiss()
                    }) { Text("Smazat") }
                }
                TextButton(onClick = onDismiss) { Text("Zrušit") }
            }
        },
    )

    if (pickingDates) {
        DateRangeDialog(start, end, onDismiss = { pickingDates = false }) { from, to ->
            start = from
            end = to
            pickingDates = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(start: Long, end: Long, onDismiss: () -> Unit, onConfirm: (Long, Long) -> Unit) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = start * DAY_MS,
        initialSelectedEndDateMillis = end * DAY_MS,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedStartDateMillis?.let { from ->
                    onConfirm(from / DAY_MS, (state.selectedEndDateMillis ?: from) / DAY_MS)
                }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    ) {
        DateRangePicker(state, Modifier.weight(1f))
    }
}

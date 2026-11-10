package com.justsomepren.gallery.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.formatRange

/** The trips found in the photos: tick the ones to keep, they become events (and albums). */
@Composable
fun TripsDialog(vm: GalleryViewModel, onDismiss: () -> Unit) {
    val trips by vm.prefs.trips.collectAsStateWithLifecycle()
    var chosen by rememberSaveable { mutableStateOf(trips.map { it.id }.toSet()) }
    var withAlbums by rememberSaveable { mutableStateOf(true) }
    var renaming by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nalezené výlety") },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(trips, key = { it.id }) { trip ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                chosen = if (trip.id in chosen) chosen - trip.id else chosen + trip.id
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(trip.id in chosen, onCheckedChange = null)
                            Column(Modifier.weight(1f)) {
                                Text(trip.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${formatRange(trip.startDay, trip.endDay)} · ${trip.photos} fotek",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (trip.note.isNotEmpty()) {
                                    Text(trip.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                            IconButton(onClick = { renaming = trip.id }) { Icon(Icons.Default.Edit, "Přejmenovat") }
                        }
                    }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vytvořit i alba", Modifier.weight(1f))
                    Switch(withAlbums, onCheckedChange = { withAlbums = it })
                }
                Hint("Alba jsou jen v aplikaci (odkazy na fotky), nic se nenahrává. Tužkou výlet přejmenuješ.")
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = {
                vm.createTrips(chosen, withAlbums)
                onDismiss()
            }) { Text("Vytvořit (${chosen.size})") }
        },
        dismissButton = {
            TextButton(onClick = {
                vm.prefs.removeTrips(trips.map { it.id }.toSet())
                onDismiss()
            }) { Text("Zahodit vše") }
        },
    )

    trips.firstOrNull { it.id == renaming }?.let { trip ->
        TextInputDialog(title = "Název výletu", confirm = "Uložit", initial = trip.name, onDismiss = { renaming = null }) {
            vm.prefs.renameTrip(trip.id, it.trim().ifBlank { trip.name })
            renaming = null
        }
    }
}

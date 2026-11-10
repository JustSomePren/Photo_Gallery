package com.justsomepren.gallery.ui

import android.app.Activity
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.GooglePhotos
import com.justsomepren.gallery.data.MediaItem
import kotlinx.coroutines.launch

/**
 * The photos verified in Google Photos: the user picks which go. Deleting needs a confirmation here,
 * a fresh check with Google right then, and Android's own dialog.
 */
@Composable
fun FreeUpDialog(photos: List<MediaItem>, vm: GalleryViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Kept in the ViewModel: a big list would not fit in the saved screen state.
    var selected by vm.selection("free-up")
    var confirming by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var deletingBytes by rememberSaveable { mutableStateOf(0L) }
    val chosen = photos.filter { it.id in selected }
    val size = { items: List<MediaItem> -> Formatter.formatShortFileSize(context, items.sumOf { it.size }) }

    val androidConfirm = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            toast(context, "Uvolněno ${Formatter.formatShortFileSize(context, deletingBytes)}")
            selected = emptySet()
            vm.onFreed()
            onDismiss()
        }
    }

    fun delete() {
        working = true
        scope.launch {
            val confirmed = runCatching { vm.confirmNow(chosen) }.getOrNull()
            working = false
            when {
                confirmed == null -> toast(context, "Google teď nejde ověřit – nic se nesmazalo.")
                confirmed.isEmpty() -> toast(context, "Žádnou vybranou fotku teď nejde ověřit – nic se nesmazalo.")
                else -> {
                    if (confirmed.size < chosen.size) {
                        toast(context, "${chosen.size - confirmed.size} už nejde ověřit, zůstanou v telefonu.")
                    }
                    val batch = confirmed.take(GooglePhotos.MAX_ITEMS)
                    deletingBytes = batch.sumOf { it.size }
                    val request = MediaStore.createDeleteRequest(context.contentResolver, batch.map { it.uri })
                    androidConfirm.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ověřeno v Google Fotkách", style = MaterialTheme.typography.titleLarge)
                Hint("Vyber, co smazat z telefonu. V Google Fotkách zůstanou.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vybráno ${chosen.size} z ${photos.size} · ${size(chosen)}", Modifier.weight(1f))
                    TextButton(onClick = {
                        selected = if (chosen.size == photos.size) emptySet() else photos.map { it.id }.toSet()
                    }) { Text(if (chosen.size == photos.size) "Zrušit výběr" else "Vybrat vše") }
                }
                LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f)) {
                    items(photos, key = { it.id }) { photo ->
                        val on = photo.id in selected
                        Box(
                            Modifier.aspectRatio(1f).padding(1.dp).clickable {
                                selected = if (on) selected - photo.id else selected + photo.id
                            },
                        ) {
                            Thumbnail(photo.uri, gridThumbPx(), Modifier.fillMaxSize())
                            if (!on) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
                            Icon(
                                if (on) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                null,
                                tint = if (on) MaterialTheme.colorScheme.primary else Color.White,
                                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(20.dp),
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("Zavřít") }
                    Button(
                        onClick = { confirming = true },
                        enabled = chosen.isNotEmpty() && !working,
                        modifier = Modifier.weight(1f),
                    ) { Text(if (working) "Ověřuji u Googlu…" else "Smazat vybrané (${chosen.size})") }
                }
            }
        }
    }

    // Always asked here, also when Android would not ask (with "manage media" allowed).
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Smazat ${chosen.size} z telefonu?") },
            text = {
                Text(
                    "Uvolní se ${size(chosen)}. V Google Fotkách zůstanou. Těsně před smazáním každou ještě " +
                        "jednou ověřím u Googlu; co nepůjde ověřit, zůstane v telefonu.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    delete()
                }) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Zrušit") } },
        )
    }
}

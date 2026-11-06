package com.justsomepren.gallery.ui

import android.app.Activity
import android.content.Context
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.GooglePhotos
import com.justsomepren.gallery.data.MediaItem

/** Actions shared by the grid and the photo viewer. */
class MediaActions(
    private val context: Context,
    private val favoriteLauncher: ActivityResultLauncher<IntentSenderRequest>,
) {
    var sending by mutableStateOf<List<MediaItem>?>(null)
    var adding by mutableStateOf<List<MediaItem>?>(null)

    fun toggleFavorite(items: List<MediaItem>) {
        if (items.isEmpty()) return
        val value = !items.all { it.isFavorite }
        val request = MediaStore.createFavoriteRequest(context.contentResolver, items.map { it.uri }, value)
        favoriteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    fun send(items: List<MediaItem>) { sending = items }

    fun addToAlbum(items: List<MediaItem>) { adding = items }

    fun share(items: List<MediaItem>) = GooglePhotos.share(context, items)
}

@Composable
fun rememberMediaActions(vm: GalleryViewModel): MediaActions {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) vm.refresh()
    }
    val actions = remember { MediaActions(context, launcher) }

    actions.sending?.let { items -> SendDialog(items, vm) { actions.sending = null } }
    actions.adding?.let { items -> AlbumPickerDialog(items, vm) { actions.adding = null } }
    return actions
}

@Composable
private fun SendDialog(items: List<MediaItem>, vm: GalleryViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val targets by vm.prefs.targets.collectAsStateWithLifecycle()
    PickerDialog(title = "Poslat ${items.size} do cloudu", onDismiss = onDismiss) {
        if (targets.isEmpty()) {
            item { Text("Zatím nemáš žádný cíl. Přidej ho v Nastavení.") }
        }
        items(targets) { target ->
            PickerRow(target.name, Icons.Default.CloudUpload) {
                GooglePhotos.send(context, target, items)
                onDismiss()
            }
        }
        item {
            PickerRow("Jiná aplikace…", Icons.Default.Share) {
                GooglePhotos.share(context, items)
                onDismiss()
            }
        }
    }
}

@Composable
private fun AlbumPickerDialog(items: List<MediaItem>, vm: GalleryViewModel, onDismiss: () -> Unit) {
    val albums by vm.prefs.albums.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val ids = items.map { it.id }

    if (creating) {
        TextInputDialog(title = "Nové album", confirm = "Vytvořit", onDismiss = onDismiss) {
            vm.prefs.createAlbum(it, ids)
            onDismiss()
        }
        return
    }
    PickerDialog(title = "Přidat do alba", onDismiss = onDismiss) {
        items(albums) { album ->
            PickerRow(album.name, Icons.Default.PhotoAlbum) {
                vm.prefs.addToAlbum(album.id, ids)
                onDismiss()
            }
        }
        item { PickerRow("Nové album…", Icons.Default.Add) { creating = true } }
    }
}

@Composable
private fun PickerDialog(
    title: String,
    onDismiss: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { LazyColumn(Modifier.heightIn(max = 400.dp), content = content) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

@Composable
private fun PickerRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun TextInputDialog(
    title: String,
    confirm: String,
    initial: String = "",
    onDismiss: () -> Unit,
    extra: @Composable () -> Unit = {},
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge)
                extra()
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zrušit") } },
    )
}

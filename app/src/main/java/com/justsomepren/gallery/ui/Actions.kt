package com.justsomepren.gallery.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.GooglePhotos
import com.justsomepren.gallery.data.MediaItem

/** Which dialog is open and for which photos. */
class OpenDialogs {
    var sending by mutableStateOf<List<MediaItem>?>(null)
    var adding by mutableStateOf<List<MediaItem>?>(null)
    var moving by mutableStateOf<List<MediaItem>?>(null)
    var info by mutableStateOf<MediaItem?>(null)
}

/** Actions shared by the grid and the photo viewer. */
class MediaActions(private val context: Context, private val vm: GalleryViewModel) {
    lateinit var launcher: ActivityResultLauncher<IntentSenderRequest>

    // Kept in the ViewModel, so turning the phone does not close an open dialog.
    private val dialogs = vm.dialogs

    // Actions that hand photos to Android or another app return false when there are too many,
    // so the selection stays.
    fun toggleFavorite(items: List<MediaItem>): Boolean {
        if (items.isEmpty() || tooMany(context, items)) return false
        val value = !items.all { it.isFavorite }
        ask(MediaStore.createFavoriteRequest(context.contentResolver, items.map { it.uri }, value))
        return true
    }

    fun moveTo(items: List<MediaItem>, path: String) {
        if (tooMany(context, items)) return
        ask(MediaStore.createWriteRequest(context.contentResolver, items.map { it.uri })) { vm.move(items, path) }
    }

    /** The queue takes any number of photos, so the dialog always opens. */
    fun send(items: List<MediaItem>) { dialogs.sending = items }

    fun addToAlbum(items: List<MediaItem>) { dialogs.adding = items }

    fun move(items: List<MediaItem>): Boolean {
        if (tooMany(context, items)) return false
        dialogs.moving = items
        return true
    }

    fun showInfo(item: MediaItem) { dialogs.info = item }

    /** Into Android's trash, or back out of it; Android empties it after 30 days. */
    fun trash(items: List<MediaItem>, restore: Boolean = false): Boolean {
        val local = items.filterNot { it.cloud }
        if (local.isEmpty() || tooMany(context, local)) return false
        ask(MediaStore.createTrashRequest(context.contentResolver, local.map { it.uri }, !restore)) {
            Toast.makeText(context, if (restore) "Obnoveno" else "V koši – lze obnovit 30 dní (Alba → Koš)", Toast.LENGTH_SHORT).show()
        }
        return true
    }

    fun share(items: List<MediaItem>): Boolean {
        if (tooMany(context, items)) return false
        GooglePhotos.share(context, items)
        return true
    }

    fun onResult(granted: Boolean) {
        if (granted) {
            vm.afterGrant()
            vm.refresh()
        }
        vm.afterGrant = {}
    }

    /** Android asks the user to confirm changes of photos (skipped with "manage media" access). */
    private fun ask(request: PendingIntent, then: () -> Unit = {}) {
        vm.afterGrant = then
        launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }
}

@Composable
fun rememberMediaActions(vm: GalleryViewModel): MediaActions {
    val context = LocalContext.current
    val actions = remember { MediaActions(context, vm) }
    actions.launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        actions.onResult(it.resultCode == Activity.RESULT_OK)
    }

    val dialogs = vm.dialogs
    dialogs.sending?.let { items -> SendDialog(items, vm) { dialogs.sending = null } }
    dialogs.adding?.let { items -> AlbumPickerDialog(items, vm) { dialogs.adding = null } }
    dialogs.moving?.let { items -> MoveDialog(items, vm, actions) { dialogs.moving = null } }
    dialogs.info?.let { item -> InfoDialog(item, vm) { dialogs.info = null } }
    return actions
}

@Composable
private fun SendDialog(items: List<MediaItem>, vm: GalleryViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val targets by vm.prefs.targets.collectAsStateWithLifecycle()
    val accounts by vm.prefs.accounts.collectAsStateWithLifecycle()
    // A running event with an album is the most likely place for these photos.
    var album by rememberSaveable {
        mutableStateOf(vm.prefs.events.value.firstOrNull { it.isActive && it.album != null }?.album.orEmpty())
    }
    PickerDialog(title = "Poslat ${items.size} do cloudu", onDismiss = onDismiss) {
        item { DialogLabel("Do fronty – odešle se samo na Wi-Fi") }
        if (accounts.isEmpty()) {
            item { Hint("Google účet přidáš v záložce Odesílání.") }
        } else {
            item {
                OutlinedTextField(album, { album = it }, label = { Text("Do alba (nepovinné)") }, singleLine = true)
            }
            items(accounts) { account ->
                PickerRow(account, Icons.Default.CloudQueue) {
                    val added = vm.enqueue(items, account, album)
                    toast(context, (if (added > 0) "Ve frontě: $added" else "Nic nového k odeslání") + skippedNote(items))
                    onDismiss()
                }
            }
        }
        item { DialogLabel("Hned přes aplikaci – jen fotky z fotoaparátu") }
        items(targets) { target ->
            PickerRow(target.name, Icons.Default.CloudUpload) {
                val camera = items.filter { it.isCameraShot }
                if (!tooMany(context, camera)) {
                    if (camera.isNotEmpty()) GooglePhotos.send(context, target, camera)
                    if (camera.size < items.size) toast(context, "Posláno ${camera.size}" + skippedNote(items))
                    onDismiss()
                }
            }
        }
        item {
            PickerRow("Jiná aplikace…", Icons.Default.Share) {
                val camera = items.filter { it.isCameraShot }
                if (!tooMany(context, camera)) {
                    if (camera.isNotEmpty()) GooglePhotos.share(context, camera)
                    if (camera.size < items.size) toast(context, "Posláno ${camera.size}" + skippedNote(items))
                    onDismiss()
                }
            }
        }
    }
}

/** Screenshots, downloads and chat pictures never go to the cloud. */
private fun skippedNote(items: List<MediaItem>): String {
    val skipped = items.count { !it.isCameraShot }
    return if (skipped > 0) " · vynecháno $skipped (ne z fotoaparátu)" else ""
}

@Composable
private fun DialogLabel(text: String) = Text(
    text,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
)

@Composable
private fun AlbumPickerDialog(items: List<MediaItem>, vm: GalleryViewModel, onDismiss: () -> Unit) {
    val albums by vm.prefs.albums.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }

    if (creating) {
        TextInputDialog(title = "Nové album", confirm = "Vytvořit", onDismiss = onDismiss) {
            vm.prefs.createAlbum(it, items)
            onDismiss()
        }
        return
    }
    PickerDialog(title = "Přidat do alba", onDismiss = onDismiss) {
        items(albums) { album ->
            PickerRow(album.name, Icons.Default.PhotoAlbum) {
                vm.prefs.addToAlbum(album.id, items)
                onDismiss()
            }
        }
        item { PickerRow("Nové album…", Icons.Default.Add) { creating = true } }
    }
}

/** Photos may only live under DCIM/ or Pictures/, so only those folders are offered. */
@Composable
private fun MoveDialog(items: List<MediaItem>, vm: GalleryViewModel, actions: MediaActions, onDismiss: () -> Unit) {
    val media by vm.media.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    val folders = remember(media) {
        media.orEmpty().map { it.relativePath }.distinct()
            .filter { (it.startsWith("DCIM/") || it.startsWith("Pictures/")) && !it.startsWith("DCIM/Camera/", ignoreCase = true) }
            .sorted()
    }
    fun moveTo(path: String) {
        actions.moveTo(items, path)
        onDismiss()
    }

    if (creating) {
        TextInputDialog(title = "Nová složka", confirm = "Přesunout", onDismiss = onDismiss) {
            moveTo("Pictures/$it/")
        }
        return
    }
    PickerDialog(title = "Přesunout ${items.size} do složky", onDismiss = onDismiss) {
        items(folders) { path -> PickerRow(path.trimEnd('/'), Icons.Default.Folder) { moveTo(path) } }
        item { PickerRow("Nová složka…", Icons.Default.CreateNewFolder) { creating = true } }
    }
}

@Composable
private fun InfoDialog(item: MediaItem, vm: GalleryViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val details = rememberLoaded(item.id) { vm.details(item) }
    val location = details?.location

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Informace") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                details?.rows?.forEach { (label, value) ->
                    Column {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(value)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zavřít") } },
        dismissButton = {
            if (location != null) {
                TextButton(onClick = { GooglePhotos.showOnMap(context, location[0], location[1]) }) { Text("Na mapě") }
            }
        },
    )
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
    var text by rememberSaveable { mutableStateOf(initial) }
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

/** Android can hand only so many photos to another app at once. */
private fun tooMany(context: Context, items: List<MediaItem>): Boolean {
    val tooMany = items.size > GooglePhotos.MAX_ITEMS
    if (tooMany) {
        Toast.makeText(context, "Najednou jde nejvýš ${GooglePhotos.MAX_ITEMS} fotek", Toast.LENGTH_LONG).show()
    }
    return tooMany
}

package com.justsomepren.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.data.title
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One screen for all photos, favorites and every album. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaBrowser(
    source: Source,
    items: List<MediaItem>,
    vm: GalleryViewModel,
    gridState: LazyGridState,
    onOpen: (MediaItem) -> Unit,
    onBack: (() -> Unit)? = null,
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val actions = rememberMediaActions(vm)
    val album = source as? Source.Album
    val picked = items.filter { it.id in selected }

    fun toggle(ids: List<Long>) {
        selected = if (selected.containsAll(ids)) selected - ids.toSet() else selected + ids
    }
    fun done() {
        selected = emptySet()
        menu = false
    }

    BackHandler(selected.isNotEmpty()) { done() }

    Scaffold(
        topBar = {
            if (picked.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${picked.size}") },
                    navigationIcon = {
                        IconButton(onClick = ::done) { Icon(Icons.Default.Close, "Zrušit výběr") }
                    },
                    actions = {
                        IconButton(onClick = { selected = items.map { it.id }.toSet() }) {
                            Icon(Icons.Default.SelectAll, "Vybrat vše")
                        }
                        FavoriteButton(picked) { actions.toggleFavorite(picked); done() }
                        IconButton(onClick = { actions.send(picked); done() }) {
                            Icon(Icons.Default.CloudUpload, "Poslat do cloudu")
                        }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Další") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem({ Text("Přidat do alba") }, onClick = { actions.addToAlbum(picked); done() })
                            if (album != null) {
                                DropdownMenuItem({ Text("Odebrat z alba") }, onClick = {
                                    vm.prefs.removeFromAlbum(album.id, picked.map { it.id })
                                    done()
                                })
                            }
                            DropdownMenuItem({ Text("Sdílet") }, onClick = { actions.share(picked); done() })
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(source.title) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zpět") }
                        }
                    },
                    actions = {
                        extraActions()
                        if (onBack != null && items.isNotEmpty()) {
                            IconButton(onClick = { selected = items.map { it.id }.toSet() }) {
                                Icon(Icons.Default.SelectAll, "Vybrat celé album")
                            }
                        }
                        if (album != null) {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Další") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem({ Text("Smazat album") }, onClick = { menu = false; confirmDelete = true })
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Žádné fotky", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            MediaGrid(
                items = items,
                selected = selected,
                state = gridState,
                contentPadding = padding,
                onClick = { if (selected.isEmpty()) onOpen(it) else toggle(listOf(it.id)) },
                onLongClick = { toggle(listOf(it.id)) },
                onHeaderClick = { group -> if (selected.isNotEmpty()) toggle(group.map { it.id }) },
            )
        }
    }

    if (confirmDelete && album != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Smazat album „${album.name}“?") },
            text = { Text("Fotky v telefonu zůstanou, zmizí jen album.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.prefs.deleteAlbum(album.id)
                    onBack?.invoke()
                }) { Text("Smazat") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Zrušit") } },
        )
    }
}

@Composable
fun FavoriteButton(items: List<MediaItem>, onClick: () -> Unit) {
    val all = items.isNotEmpty() && items.all { it.isFavorite }
    IconButton(onClick = onClick) {
        Icon(
            if (all) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            if (all) "Odebrat z oblíbených" else "Přidat do oblíbených",
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    items: List<MediaItem>,
    selected: Set<Long>,
    state: LazyGridState,
    contentPadding: PaddingValues,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
    onHeaderClick: (List<MediaItem>) -> Unit,
) {
    val columns = if (LocalSaver.current) 5 else 4
    val thumbPx = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp / columns).roundToPx() }
    val months = remember(items) {
        val format = SimpleDateFormat("LLLL yyyy", Locale("cs"))
        items.groupBy { format.format(Date(it.date)) }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        months.forEach { (month, group) ->
            item(key = "month $month", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    month.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.clickable { onHeaderClick(group) }.padding(12.dp, 16.dp, 12.dp, 8.dp),
                )
            }
            items(group, key = { it.id }) { item ->
                val isSelected = item.id in selected
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item) }),
                ) {
                    Thumbnail(item.uri, thumbPx, Modifier.fillMaxSize())
                    if (item.isVideo) CornerIcon(Icons.Default.PlayCircle, Alignment.BottomStart)
                    if (item.isFavorite) CornerIcon(Icons.Default.Favorite, Alignment.BottomEnd)
                    if (isSelected) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
                        CornerIcon(Icons.Default.CheckCircle, Alignment.TopStart, MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CornerIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    alignment: Alignment,
    tint: Color = Color.White,
) {
    Icon(icon, null, tint = tint, modifier = Modifier.align(alignment).padding(4.dp).size(18.dp))
}

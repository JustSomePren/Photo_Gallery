package com.justsomepren.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.CustomAlbum
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.data.filterBy
import com.justsomepren.gallery.data.title

private data class AlbumEntry(val source: Source, val cover: MediaItem?, val count: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    media: List<MediaItem>,
    albums: List<CustomAlbum>,
    events: List<Event>,
    vm: GalleryViewModel,
    onOpen: (Source) -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    val own = remember(media, albums) {
        albums.map { album ->
            val source = Source.Album(album.id, album.name)
            val list = media.filterBy(source, albums)
            AlbumEntry(source, list.firstOrNull(), list.size)
        }
    }
    // Trips and vacations, including those from the Kalendář app
    val periods = remember(media, events) {
        events.sortedByDescending { it.startDay }.map { event ->
            val list = media.filterBy(event.source, albums)
            AlbumEntry(event.source, list.firstOrNull(), list.size)
        }
    }
    val folders = remember(media) {
        media.groupBy { it.bucketId }
            .map { (id, list) -> AlbumEntry(Source.Folder(id, list.first().bucketName), list.first(), list.size) }
            .sortedByDescending { it.count }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Alba") },
                actions = {
                    IconButton(onClick = { creating = true }) { Icon(Icons.Default.Add, "Nové album") }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (own.isNotEmpty()) section("Moje alba", own, onOpen)
            if (periods.isNotEmpty()) section("Události", periods, onOpen)
            section("Složky v telefonu", folders, onOpen)
        }
    }

    if (creating) {
        TextInputDialog(title = "Nové album", confirm = "Vytvořit", onDismiss = { creating = false }) {
            vm.prefs.createAlbum(it)
            creating = false
        }
    }
}

private fun LazyGridScope.section(title: String, entries: List<AlbumEntry>, onOpen: (Source) -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    }
    items(entries, key = { it.source.toString() }) { entry ->
        Column(Modifier.clickable { onOpen(entry.source) }) {
            val shape = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
            if (entry.cover != null) {
                Thumbnail(entry.cover.uri, 400, shape)
            } else {
                Box(shape.background(MaterialTheme.colorScheme.surfaceVariant))
            }
            Text(entry.source.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text("${entry.count}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

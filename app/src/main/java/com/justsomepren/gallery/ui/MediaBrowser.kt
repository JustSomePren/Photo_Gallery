package com.justsomepren.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
    var selected by rememberSaveable(source.toString(), stateSaver = SelectionSaver) { mutableStateOf(emptySet<Long>()) }
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
    LaunchedEffect(items) {
        val present = items.map { it.id }.toSet()
        if (!present.containsAll(selected)) selected = selected intersect present
    }

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
                            DropdownMenuItem({ Text("Přesunout do složky") }, onClick = { actions.move(picked); done() })
                            if (album != null) {
                                DropdownMenuItem({ Text("Odebrat z alba") }, onClick = {
                                    vm.prefs.removeFromAlbum(album.id, picked)
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
        val refreshing by vm.refreshing.collectAsStateWithLifecycle()
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.refresh(manual = true) },
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    start = padding.calculateStartPadding(LocalLayoutDirection.current),
                    end = padding.calculateEndPadding(LocalLayoutDirection.current),
                )
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)),
        ) {
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
                    Text("Žádné fotky", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                MediaGrid(
                    items = items,
                    selected = selected,
                    state = gridState,
                    bottomPadding = padding.calculateBottomPadding(),
                    vm = vm,
                    onClick = { if (selected.isEmpty()) onOpen(it) else toggle(listOf(it.id)) },
                    onLongClick = { toggle(listOf(it.id)) },
                    onHeaderClick = { group -> toggle(group.map { it.id }) },
                )
            }
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

private val SelectionSaver = Saver<Set<Long>, LongArray>({ it.toLongArray() }, { it.toSet() })

private sealed interface GridEntry {
    val month: String

    data class Header(override val month: String, val items: List<MediaItem>) : GridEntry
    data class Photo(override val month: String, val item: MediaItem) : GridEntry
}

private fun buildEntries(items: List<MediaItem>): List<GridEntry> {
    val format = SimpleDateFormat("LLLL yyyy", Locale("cs"))
    return items
        .groupBy { format.format(Date(it.date)).replaceFirstChar { c -> c.uppercase() } }
        .flatMap { (month, group) -> listOf(GridEntry.Header(month, group)) + group.map { GridEntry.Photo(month, it) } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    items: List<MediaItem>,
    selected: Set<Long>,
    state: LazyGridState,
    bottomPadding: Dp,
    vm: GalleryViewModel,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
    onHeaderClick: (List<MediaItem>) -> Unit,
) {
    val columns = if (LocalSaver.current) 5 else 4
    val thumbPx = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp.dp / 4).roundToPx() }
    val entries = remember(items) { buildEntries(items) }

    // Coming back from the viewer: make sure the last viewed photo is on screen.
    LaunchedEffect(Unit) {
        val id = vm.returnTo ?: return@LaunchedEffect
        vm.returnTo = null
        val index = entries.indexOfFirst { it is GridEntry.Photo && it.item.id == id }
        snapshotFlow { state.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
        if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.key == id }) state.scrollToItem(index)
    }

    Box {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = PaddingValues(bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(
                entries,
                key = { if (it is GridEntry.Photo) it.item.id else "month ${it.month}" },
                span = { GridItemSpan(if (it is GridEntry.Header) maxLineSpan else 1) },
                contentType = { it::class },
            ) { entry ->
                when (entry) {
                    is GridEntry.Header -> Text(
                        entry.month,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.clickable { onHeaderClick(entry.items) }.padding(12.dp, 16.dp, 12.dp, 8.dp),
                    )
                    is GridEntry.Photo -> {
                        val item = entry.item
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item) }),
                        ) {
                            Thumbnail(item.uri, thumbPx, Modifier.fillMaxSize())
                            if (item.isVideo) CornerIcon(Icons.Default.PlayCircle, Alignment.BottomStart)
                            if (item.isFavorite) CornerIcon(Icons.Default.Favorite, Alignment.BottomEnd)
                            if (item.id in selected) {
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
                                CornerIcon(Icons.Default.CheckCircle, Alignment.TopStart, MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        if (entries.size > 60) FastScroller(state, entries, Modifier.padding(bottom = bottomPadding))
    }
}

/** Drag along the right edge to jump through months quickly. */
@Composable
private fun FastScroller(state: LazyGridState, entries: List<GridEntry>, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var height by remember { mutableIntStateOf(1) }
    val thumbHeight = 48.dp
    val thumbPx = with(LocalDensity.current) { thumbHeight.roundToPx() }
    val exclusionPad = with(LocalDensity.current) { 70.dp.toPx() }
    val progress by remember(entries) {
        derivedStateOf {
            val range = entries.size - state.layoutInfo.visibleItemsInfo.size
            (state.firstVisibleItemIndex.toFloat() / range.coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    }
    val label by remember(entries) {
        derivedStateOf { entries.getOrNull(state.firstVisibleItemIndex)?.month.orEmpty() }
    }
    val thumbOffset: Density.() -> IntOffset = { IntOffset(0, (progress * (height - thumbPx)).roundToInt()) }

    fun jump(y: Float) {
        val range = entries.size - state.layoutInfo.visibleItemsInfo.size
        val index = ((y / height).coerceIn(0f, 1f) * range.coerceAtLeast(0)).roundToInt()
        scope.launch { state.scrollToItem(index) }
    }

    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) {
            recentlyScrolled = true
        } else {
            delay(1500)
            recentlyScrolled = false
        }
    }

    if (!dragging && !recentlyScrolled) return
    Box(modifier.fillMaxSize().onSizeChanged { height = it.height }) {
        if (dragging) {
            Text(
                label,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(thumbOffset)
                    .padding(end = 40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(32.dp)
                .systemGestureExclusion { coordinates ->
                    val top = progress * (height - thumbPx)
                    Rect(0f, top - exclusionPad, coordinates.size.width.toFloat(), top + thumbPx + exclusionPad)
                }
                .pointerInput(entries) {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true; jump(it.y) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> jump(change.position.y) }
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(thumbOffset)
                    .padding(end = 4.dp)
                    .size(8.dp, thumbHeight)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
            )
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

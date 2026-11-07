package com.justsomepren.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.CloudSync
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.day
import com.justsomepren.gallery.data.formatRange
import com.justsomepren.gallery.data.formatWeekday
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.data.month
import com.justsomepren.gallery.data.monthTitle
import com.justsomepren.gallery.data.title

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
    header: @Composable () -> Unit = {},
    extraActions: @Composable RowScope.() -> Unit = {},
) {
    var selected by rememberSaveable(source.toString(), stateSaver = SelectionSaver) { mutableStateOf(emptySet<Long>()) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
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
                        FavoriteButton(picked) { if (actions.toggleFavorite(picked)) done() }
                        IconButton(onClick = { actions.send(picked); done() }) {
                            Icon(Icons.Default.CloudUpload, "Poslat do cloudu")
                        }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Další") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem({ Text("Přidat do alba") }, onClick = { actions.addToAlbum(picked); done() })
                            DropdownMenuItem({ Text("Přesunout do složky") }, onClick = { if (actions.move(picked)) done() else menu = false })
                            if (album != null) {
                                DropdownMenuItem({ Text("Odebrat z alba") }, onClick = {
                                    vm.prefs.removeFromAlbum(album.id, picked)
                                    done()
                                })
                            }
                            DropdownMenuItem({ Text("Sdílet") }, onClick = { if (actions.share(picked)) done() else menu = false })
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
                    header = header,
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
    data class EventStart(override val month: String, val event: Event, val firstId: Long) : GridEntry
    data class Photo(override val month: String, val item: MediaItem, val event: Event?) : GridEntry
}

/** Months, and inside them a colored row where the photos of an event begin. */
private fun buildEntries(items: List<MediaItem>, events: List<Event>): List<GridEntry> =
    items.groupBy { monthTitle(it.month) }.flatMap { (month, group) ->
        val entries = mutableListOf<GridEntry>(GridEntry.Header(month, group))
        var current: Event? = null
        for (item in group) {
            val event = events.firstOrNull { it.covers(item.day) }
            if (event != null && event != current) entries += GridEntry.EventStart(month, event, item.id)
            current = event
            entries += GridEntry.Photo(month, item, event)
        }
        entries
    }

/** The first photo on screen: its date and event, shown while scrolling. */
private fun List<GridEntry>.photoAt(index: Int): GridEntry.Photo? =
    asSequence().drop(index.coerceAtLeast(0)).take(12).filterIsInstance<GridEntry.Photo>().firstOrNull()

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    items: List<MediaItem>,
    selected: Set<Long>,
    state: LazyGridState,
    bottomPadding: Dp,
    vm: GalleryViewModel,
    header: @Composable () -> Unit,
    onClick: (MediaItem) -> Unit,
    onLongClick: (MediaItem) -> Unit,
    onHeaderClick: (List<MediaItem>) -> Unit,
) {
    val columns = if (LocalSaver.current) 5 else 4
    val thumbPx = gridThumbPx()
    val events by vm.prefs.events.collectAsStateWithLifecycle()
    val entries = remember(items, events) { buildEntries(items, events) }
    // Grid position 0 is the header, so entry i sits at position i + 1.
    val current by remember(entries) { derivedStateOf { entries.photoAt(state.firstVisibleItemIndex - 1) } }
    val scrolling = rememberRecentlyScrolled(state)

    // Coming back from the viewer: make sure the last viewed photo is on screen.
    LaunchedEffect(Unit) {
        val id = vm.returnTo ?: return@LaunchedEffect
        vm.returnTo = null
        val index = entries.indexOfFirst { it is GridEntry.Photo && it.item.id == id }
        snapshotFlow { state.layoutInfo.visibleItemsInfo }.first { it.isNotEmpty() }
        if (index >= 0 && state.layoutInfo.visibleItemsInfo.none { it.key == id }) state.scrollToItem(index + 1)
    }

    Box {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = PaddingValues(bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
            items(
                entries,
                key = {
                    when (it) {
                        is GridEntry.Photo -> it.item.id
                        is GridEntry.EventStart -> "event ${it.event.id} ${it.firstId}"
                        is GridEntry.Header -> "month ${it.month}"
                    }
                },
                span = { GridItemSpan(if (it is GridEntry.Photo) 1 else maxLineSpan) },
                contentType = { it::class },
            ) { entry ->
                when (entry) {
                    is GridEntry.Header -> Text(
                        entry.month,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.clickable { onHeaderClick(entry.items) }.padding(12.dp, 16.dp, 12.dp, 8.dp),
                    )
                    is GridEntry.EventStart -> EventRow(entry.event) { vm.editingEvent = entry.event }
                    is GridEntry.Photo -> {
                        val item = entry.item
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .combinedClickable(onClick = { onClick(item) }, onLongClick = { onLongClick(item) }),
                        ) {
                            Thumbnail(item.uri, thumbPx, Modifier.fillMaxSize())
                            entry.event?.let {
                                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color(it.color)))
                            }
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
        current?.takeIf { scrolling && state.firstVisibleItemIndex > 0 }?.let {
            ContextPill(it, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        }
        if (entries.size > 60) FastScroller(state, current, Modifier.padding(bottom = bottomPadding))
    }
}

/** Where the event's photos begin: its color, name and dates. */
@Composable
private fun EventRow(event: Event, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(12.dp).background(Color(event.color), CircleShape))
        Text(
            event.name,
            style = MaterialTheme.typography.titleSmall,
            color = Color(event.color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            formatRange(event.startDay, event.endDay),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        if (event.autoSend) {
            Icon(Icons.Default.CloudSync, "Posílá automaticky", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** "čt 12. 11. 2026 · ● Dovča Praha" floating over the grid. */
@Composable
private fun ContextPill(photo: GridEntry.Photo, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(formatWeekday(photo.item.day), color = MaterialTheme.colorScheme.inverseOnSurface)
        photo.event?.let {
            Box(Modifier.size(10.dp).background(Color(it.color), CircleShape))
            Text(it.name, color = MaterialTheme.colorScheme.inverseOnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** True while the list scrolls and a moment after, so labels do not flicker away. */
@Composable
private fun rememberRecentlyScrolled(state: LazyGridState): Boolean {
    var recentlyScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) {
            recentlyScrolled = true
        } else {
            delay(1500)
            recentlyScrolled = false
        }
    }
    return recentlyScrolled
}

/**
 * A handle on the right edge to move through months quickly. Only the handle itself takes touches,
 * so taps and normal swipes near the edge still reach the photos.
 */
@Composable
private fun FastScroller(state: LazyGridState, current: GridEntry.Photo?, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableIntStateOf(1) }
    val density = LocalDensity.current
    val thumbHeight = 48.dp
    val thumbPx = with(density) { thumbHeight.roundToPx() }
    val exclusionPad = with(density) { 70.dp.toPx() }
    val track = (height - thumbPx).coerceAtLeast(1)
    val progress by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val range = info.totalItemsCount - info.visibleItemsInfo.size
            (state.firstVisibleItemIndex.toFloat() / range.coerceAtLeast(1)).coerceIn(0f, 1f)
        }
    }
    val top = if (dragging) dragTop else progress * track
    val offset: Density.() -> IntOffset = { IntOffset(0, top.roundToInt()) }

    fun moveTo(y: Float) {
        dragTop = y.coerceIn(0f, track.toFloat())
        val fraction = dragTop / track
        val total = state.layoutInfo.totalItemsCount
        val range = (total - state.layoutInfo.visibleItemsInfo.size).coerceAtLeast(0)
        // At the bottom go to the very last photo, wherever the months fall.
        val index = if (fraction >= 0.995f) total - 1 else (fraction * range).roundToInt()
        scope.launch { state.scrollToItem(index.coerceAtLeast(0)) }
    }

    val recentlyScrolled = rememberRecentlyScrolled(state)
    if (!dragging && !recentlyScrolled) return
    Box(modifier.fillMaxSize().onSizeChanged { height = it.height }) {
        if (dragging && current != null) {
            ContextPill(current, Modifier.align(Alignment.TopEnd).offset(offset).padding(end = 40.dp))
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(offset)
                .size(32.dp, thumbHeight)
                .semantics { contentDescription = "Rychlé posouvání" }
                .systemGestureExclusion { Rect(0f, -exclusionPad, it.size.width.toFloat(), it.size.height + exclusionPad) }
                .pointerInput(track) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            dragTop = progress * track
                            dragging = true
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, dy ->
                        change.consume()
                        moveTo(dragTop + dy)
                    }
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
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

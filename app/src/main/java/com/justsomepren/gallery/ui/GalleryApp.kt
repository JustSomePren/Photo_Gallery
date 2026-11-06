package com.justsomepren.gallery.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.GooglePhotos
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.formatDay
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.data.batteryFlow
import com.justsomepren.gallery.data.filterBy

/** True when the battery is low and the app should do as little work as possible. */
val LocalSaver = compositionLocalOf { false }

val MEDIA_PERMISSIONS = arrayOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VIDEO,
    Manifest.permission.ACCESS_MEDIA_LOCATION,
)

@Composable
fun GalleryApp(vm: GalleryViewModel = viewModel()) {
    val context = LocalContext.current
    val battery by remember { context.batteryFlow() }.collectAsStateWithLifecycle(null)
    val threshold by vm.prefs.threshold.collectAsStateWithLifecycle()
    val saver = battery?.saver(threshold) ?: false

    SideEffect { vm.saver = saver }
    LowerRefreshRate(saver)

    GalleryTheme(saver) {
        CompositionLocalProvider(LocalSaver provides saver) {
            Surface(Modifier.fillMaxSize()) {
                var granted by remember { mutableStateOf(hasMediaAccess(context)) }
                if (granted) Main(vm) else PermissionScreen { granted = hasMediaAccess(context) }
            }
        }
    }
}

@Composable
private fun Main(vm: GalleryViewModel) {
    LifecycleStartEffect(Unit) {
        vm.setActive(true)
        onStopOrDispose { vm.setActive(false) }
    }
    SystemBarIcons(
        light = MaterialTheme.colorScheme.background.luminance() > 0.5f && vm.stack.lastOrNull() !is Screen.Viewer,
    )
    val context = LocalContext.current
    val media by vm.media.collectAsStateWithLifecycle()
    val albums by vm.prefs.albums.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }

    val all = media ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val stack = vm.stack
    val back: () -> Unit = { stack.removeAt(stack.lastIndex) }
    BackHandler(stack.isNotEmpty(), back)

    val events by vm.prefs.events.collectAsStateWithLifecycle()
    val calendar by vm.prefs.calendarView.collectAsStateWithLifecycle()
    val openSending = { vm.tab = SENDING_TAB }

    @Composable
    fun browser(
        source: Source,
        onBack: (() -> Unit)?,
        header: @Composable () -> Unit = {},
        extraActions: @Composable RowScope.() -> Unit = {},
    ) {
        MediaBrowser(
            source = source,
            items = remember(all, albums, source) { all.filterBy(source, albums) },
            vm = vm,
            gridState = vm.gridState(source),
            onOpen = { stack.add(Screen.Viewer(source, it.id)) },
            onBack = onBack,
            header = header,
            extraActions = extraActions,
        )
    }

    @Composable
    fun RowScope.photoActions() {
        if (LocalSaver.current) {
            Icon(Icons.Default.BatterySaver, "Úsporný režim", tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = { vm.prefs.setCalendarView(!calendar) }) {
            if (calendar) Icon(Icons.Default.GridView, "Mřížka") else Icon(Icons.Default.CalendarMonth, "Kalendář")
        }
        IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Hledat v Google Fotkách") }
    }

    when (val top = stack.lastOrNull()) {
        is Screen.Viewer -> Viewer(
            items = remember(all, albums, top.source) { all.filterBy(top.source, albums) },
            startId = top.startId,
            vm = vm,
            onBack = back,
        )
        is Screen.Grid -> browser(top.source, back)
        is Screen.EventPhotos -> {
            val event = events.firstOrNull { it.id == top.eventId }
            if (event == null) {
                // Deleted in the editor.
                LaunchedEffect(Unit) { back() }
            } else {
                browser(event.source, back) {
                    IconButton(onClick = { vm.editingEvent = event }) { Icon(Icons.Default.Edit, "Upravit událost") }
                }
            }
        }
        null -> Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                NavigationBar {
                    TABS.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(
                            selected = vm.tab == index,
                            onClick = { vm.tab = index },
                            icon = { Icon(icon, null) },
                            label = { Text(label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(WindowInsets.navigationBars)) {
                when (vm.tab) {
                    0 -> if (calendar) {
                        CalendarScreen(all, events, vm, openSending, actions = { photoActions() }) { stack.add(it) }
                    } else {
                        browser(Source.All, onBack = null, header = { StatusStrip(vm, openSending) }) { photoActions() }
                    }
                    1 -> browser(Source.Favorites, onBack = null)
                    2 -> AlbumsScreen(all, albums, vm, onOpen = { stack.add(Screen.Grid(it)) })
                    SENDING_TAB -> SendingScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
    }

    vm.editingEvent?.let { EventEditor(it, vm) { vm.editingEvent = null } }

    if (searching) {
        TextInputDialog(
            title = "Hledat v Google Fotkách",
            confirm = "Hledat",
            onDismiss = { searching = false },
            onConfirm = {
                searching = false
                GooglePhotos.search(context, it)
            },
        )
    }
}

private const val SENDING_TAB = 3

private val TABS = listOf(
    "Fotky" to Icons.Default.Photo,
    "Oblíbené" to Icons.Default.Favorite,
    "Alba" to Icons.Default.PhotoAlbum,
    "Odesílání" to Icons.Default.CloudUpload,
    "Nastavení" to Icons.Default.Settings,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarScreen(
    media: List<MediaItem>,
    events: List<Event>,
    vm: GalleryViewModel,
    openSending: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    onOpen: (Screen) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Fotky") }, actions = actions) }) { padding ->
        CalendarView(
            media = media,
            events = events,
            state = vm.calendarState,
            contentPadding = padding,
            header = { StatusStrip(vm, openSending) },
            onDay = { onOpen(Screen.Grid(Source.Period(formatDay(it), it, it))) },
            onNewEvent = vm::newEvent,
            onEvent = { onOpen(Screen.EventPhotos(it.id)) },
        )
    }
}

@Composable
private fun PermissionScreen(onResult: () -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        onResult()
    }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Galerie potřebuje přístup k fotkám a videím.", style = MaterialTheme.typography.titleMedium)
        Text("Vše zůstává v telefonu, aplikace nemá přístup k internetu.")
        Button(onClick = { launcher.launch(MEDIA_PERMISSIONS) }) { Text("Povolit přístup") }
    }
}

fun hasFullAccess(context: Context) =
    context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

private fun hasMediaAccess(context: Context) = listOf(
    Manifest.permission.READ_MEDIA_IMAGES,
    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
).any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

@Composable
private fun GalleryTheme(saver: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = saver || isSystemInDarkTheme()
    var scheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    if (saver) {
        // Pure black pixels are switched off on the OLED screen.
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainer = Color.Black,
            surfaceVariant = Color(0xFF111111),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Dark icons on a light background and white icons on black (saver mode, viewer). */
@Composable
private fun SystemBarIcons(light: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

/** The Pixel screen runs at up to 120 Hz; 60 Hz is plenty for browsing on low battery. */
@Composable
private fun LowerRefreshRate(saver: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    LaunchedEffect(saver) {
        val window = activity.window
        window.attributes = window.attributes.apply { preferredRefreshRate = if (saver) 60f else 0f }
    }
}

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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.GooglePhotos
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.data.batteryFlow
import com.justsomepren.gallery.data.filterBy

/** True when the battery is low and the app should do as little work as possible. */
val LocalSaver = compositionLocalOf { false }

private sealed interface Screen {
    data class Grid(val source: Source) : Screen
    data class Viewer(val source: Source, val startId: Long) : Screen
}

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
    val context = LocalContext.current
    val media by vm.media.collectAsStateWithLifecycle()
    val albums by vm.prefs.albums.collectAsStateWithLifecycle()
    val stack = remember { mutableStateListOf<Screen>() }
    val gridStates = remember { mutableMapOf<Source, LazyGridState>() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }

    val all = media ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    fun gridState(source: Source) = gridStates.getOrPut(source) { LazyGridState() }
    fun open(source: Source) = stack.add(Screen.Grid(source))
    fun view(source: Source, id: Long) = stack.add(Screen.Viewer(source, id))
    val back: () -> Unit = { stack.removeAt(stack.lastIndex) }

    BackHandler(stack.isNotEmpty(), back)

    when (val top = stack.lastOrNull()) {
        is Screen.Viewer -> Viewer(all.filterBy(top.source, albums), top.startId, vm, back)
        is Screen.Grid -> MediaBrowser(
            source = top.source,
            items = all.filterBy(top.source, albums),
            vm = vm,
            gridState = gridState(top.source),
            onOpen = { view(top.source, it.id) },
            onBack = back,
        )
        null -> Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                NavigationBar {
                    TABS.forEachIndexed { index, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = { tab = index },
                            icon = { Icon(icon, null) },
                            label = { Text(label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(WindowInsets.navigationBars)) {
                when (tab) {
                    0, 1 -> {
                        val source = if (tab == 0) Source.All else Source.Favorites
                        MediaBrowser(
                            source = source,
                            items = all.filterBy(source, albums),
                            vm = vm,
                            gridState = gridState(source),
                            onOpen = { view(source, it.id) },
                            extraActions = {
                                if (LocalSaver.current) {
                                    Icon(Icons.Default.BatterySaver, "Úsporný režim", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { searching = true }) {
                                    Icon(Icons.Default.Search, "Hledat v Google Fotkách")
                                }
                            },
                        )
                    }
                    2 -> AlbumsScreen(all, albums, vm, onOpen = ::open)
                    else -> SettingsScreen(vm)
                }
            }
        }
    }

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

private val TABS = listOf(
    "Fotky" to Icons.Default.Photo,
    "Oblíbené" to Icons.Default.Favorite,
    "Alba" to Icons.Default.PhotoAlbum,
    "Nastavení" to Icons.Default.Settings,
)

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
        Button(onClick = {
            launcher.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO))
        }) { Text("Povolit přístup") }
    }
}

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

/** The Pixel screen runs at up to 120 Hz; 60 Hz is plenty for browsing on low battery. */
@Composable
private fun LowerRefreshRate(saver: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    LaunchedEffect(saver) {
        val window = activity.window
        window.attributes = window.attributes.apply { preferredRefreshRate = if (saver) 60f else 0f }
    }
}

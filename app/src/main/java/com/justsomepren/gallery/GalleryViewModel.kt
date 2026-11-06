package com.justsomepren.gallery

import android.app.Application
import android.content.ContentValues
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justsomepren.gallery.data.GooglePhotos
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.MediaRepository
import com.justsomepren.gallery.data.Prefs
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.data.WarmupWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data class Grid(val source: Source) : Screen
    data class Viewer(val source: Source, val startId: Long) : Screen
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository(app)
    val prefs = Prefs(app)

    private val _media = MutableStateFlow<List<MediaItem>?>(null)
    val media: StateFlow<List<MediaItem>?> = _media
    val refreshing = MutableStateFlow(false)

    // Navigation lives here, so turning the phone keeps the open screen and scroll position.
    val stack = mutableStateListOf<Screen>()
    val gridStates = mutableMapOf<Source, LazyGridState>()
    var tab by mutableIntStateOf(0)

    /** What to do once the user confirms a change in the system dialog; survives rotation. */
    var afterGrant: () -> Unit = {}

    /** The photo last shown in the viewer; the grid scrolls to it when coming back. */
    var returnTo: Long? = null

    /** In battery saver mode new photos show up when the app is reopened, not live. */
    var saver = false
    private var refreshJob: Job? = null

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (!saver) refresh(delayMs = 1000)
        }
    }

    init {
        if (!prefs.hasTargets) prefs.addTargets(GooglePhotos.defaultTargets(app))
        WarmupWorker.schedule(app, prefs.warmup.value)
    }

    fun gridState(source: Source) = gridStates.getOrPut(source) { LazyGridState() }

    fun refresh(delayMs: Long = 0, manual: Boolean = false) {
        if (manual) refreshing.value = true
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(delayMs)
            val items = withContext(Dispatchers.IO) { repo.loadAll() }
            _media.value = items
            refreshing.value = false
        }
    }

    suspend fun details(item: MediaItem) = withContext(Dispatchers.IO) { repo.details(item) }

    /** [path] is a folder like "Pictures/Trip/". Albums follow the photos, the id does not change. */
    fun move(items: List<MediaItem>, path: String) = viewModelScope.launch {
        val resolver = getApplication<Application>().contentResolver
        val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, path) }
        val failed = withContext(Dispatchers.IO) {
            items.count { runCatching { resolver.update(it.uri, values, null, null) > 0 }.getOrDefault(false).not() }
        }
        if (failed > 0) {
            Toast.makeText(getApplication(), "Nepodařilo se přesunout $failed", Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    /** Called when the app comes on / leaves the screen, so nothing runs in the background. */
    fun setActive(active: Boolean) {
        val resolver = getApplication<Application>().contentResolver
        if (active) {
            refresh()
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        } else {
            resolver.unregisterContentObserver(observer)
        }
    }

    fun setWarmup(enabled: Boolean) {
        prefs.setWarmup(enabled)
        WarmupWorker.schedule(getApplication(), enabled)
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}

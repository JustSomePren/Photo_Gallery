package com.justsomepren.gallery

import android.app.Application
import android.content.ContentValues
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.justsomepren.gallery.data.EVENT_COLORS
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.GooglePhotos
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.MediaRepository
import com.justsomepren.gallery.data.Prefs
import com.justsomepren.gallery.data.QueueItem
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.data.UploadWorker
import com.justsomepren.gallery.data.WarmupWorker
import com.justsomepren.gallery.data.today
import com.justsomepren.gallery.ui.OpenDialogs
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

    /** Photos of an event; found by id, so editing the event updates the open screen. */
    data class EventPhotos(val eventId: String) : Screen
}

class GalleryViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val repo = MediaRepository(app)
    val prefs = Prefs.get(app)

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

    @VisibleForTesting
    var loadMedia: () -> List<MediaItem> = repo::loadAll

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (!saver) refresh(delayMs = 1000)
        }
    }

    val calendarState = LazyListState()

    val dialogs = OpenDialogs()

    /** The event open in the editor, from whichever screen it was opened. */
    var editingEvent by mutableStateOf<Event?>(null)

    /** The account being connected while Google shows its consent dialog. */
    var pendingAccount: String?
        get() = saved["pendingAccount"]
        set(value) {
            saved["pendingAccount"] = value
        }

    init {
        if (!prefs.hasTargets) prefs.addTargets(GooglePhotos.defaultTargets(app))
        WarmupWorker.schedule(app, prefs.warmup.value)
        UploadWorker.schedule(app)
    }

    fun gridState(source: Source) = gridStates.getOrPut(source) { LazyGridState() }

    fun refresh(delayMs: Long = 0, manual: Boolean = false) {
        if (manual) refreshing.value = true
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(delayMs)
            val items = withContext(Dispatchers.IO) { loadMedia() }
            _media.value = items
            prefs.migrateKeys(items)
            if (prefs.collectAuto(items) > 0) UploadWorker.schedule(getApplication())
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

    /** Only photos and videos from the camera go to the queue; returns how many were added. */
    fun enqueue(items: List<MediaItem>, account: String, album: String?): Int {
        val added = prefs.enqueue(items.filter { it.isCameraShot }.map { QueueItem.of(it, account, album) })
        UploadWorker.schedule(getApplication())
        return added
    }

    fun saveEvent(event: Event) {
        prefs.saveEvent(event)
        prefs.pruneAuto()
        media.value?.let { prefs.collectAuto(it) }
        UploadWorker.schedule(getApplication())
    }

    fun newEvent(day: Long = today()) {
        val color = EVENT_COLORS[prefs.events.value.size % EVENT_COLORS.size]
        editingEvent = Event(name = "", color = color, startDay = day, endDay = day)
    }

    fun deleteEvent(event: Event) {
        prefs.deleteEvent(event)
        prefs.pruneAuto()
        // Another event over the same days may want these photos.
        media.value?.let { prefs.collectAuto(it) }
        UploadWorker.schedule(getApplication())
    }

    fun setMobileData(enabled: Boolean) {
        prefs.setMobileData(enabled)
        UploadWorker.schedule(getApplication(), restart = true)
    }

    /** A lower threshold may free a queue that waits for the charger. */
    fun setThreshold(value: Int) {
        prefs.setThreshold(value)
        UploadWorker.schedule(getApplication(), restart = true)
    }

    fun retryFailed() {
        prefs.retryFailed()
        UploadWorker.schedule(getApplication())
    }

    fun sendNow() = UploadWorker.sendNow(getApplication())

    fun setWarmup(enabled: Boolean) {
        prefs.setWarmup(enabled)
        WarmupWorker.schedule(getApplication(), enabled)
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}

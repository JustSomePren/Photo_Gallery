package com.justsomepren.gallery

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.justsomepren.gallery.data.GooglePhotos
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.MediaRepository
import com.justsomepren.gallery.data.Prefs
import com.justsomepren.gallery.data.WarmupWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = MediaRepository(app)
    val prefs = Prefs(app)

    private val _media = MutableStateFlow<List<MediaItem>?>(null)
    val media: StateFlow<List<MediaItem>?> = _media

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

    fun refresh(delayMs: Long = 0) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(delayMs)
            _media.value = withContext(Dispatchers.IO) { repo.loadAll() }
        }
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

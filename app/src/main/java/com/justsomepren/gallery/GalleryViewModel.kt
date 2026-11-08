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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.justsomepren.gallery.data.CloudLibrary
import com.justsomepren.gallery.data.CloudPhoto
import com.justsomepren.gallery.data.EVENT_COLORS
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.GooglePhotos
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.GoogleAuth
import com.justsomepren.gallery.data.MediaRepository
import com.justsomepren.gallery.data.NotOnWifi
import com.justsomepren.gallery.data.PickerApi
import com.justsomepren.gallery.data.onWifi
import com.justsomepren.gallery.data.PhotosApi
import com.justsomepren.gallery.data.Places
import com.justsomepren.gallery.data.SpotCache
import com.justsomepren.gallery.data.Timeline
import com.justsomepren.gallery.data.TripNamer
import com.justsomepren.gallery.data.TripProposal
import com.justsomepren.gallery.data.TripFinder
import com.justsomepren.gallery.data.confirmedInCloud
import com.justsomepren.gallery.data.day
import com.justsomepren.gallery.data.freeUpCandidates
import com.justsomepren.gallery.data.Prefs
import com.justsomepren.gallery.data.QueueItem
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.data.UploadWorker
import com.justsomepren.gallery.data.WarmupWorker
import com.justsomepren.gallery.data.today
import com.justsomepren.gallery.ui.ImageCache
import com.justsomepren.gallery.ui.OpenDialogs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Screen {
    data class Grid(val source: Source) : Screen
    /** [opened] makes every opening its own screen, so opening the same photo again starts from the current photos. */
    data class Viewer(val source: Source, val startId: Long, val opened: Long = System.nanoTime()) : Screen

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

    /**
     * Selected photos per screen. Kept here rather than in the saved screen state: selecting all of a big
     * library would be too large for Android to save when the app goes to the background.
     */
    private val selections = mutableMapOf<String, MutableState<Set<Long>>>()

    fun selection(key: String) = selections.getOrPut(key) { mutableStateOf(emptySet()) }

    /** The photos each open viewer started with; kept here so turning the phone shows the same photo. */
    private val viewerPhotos = mutableMapOf<Screen.Viewer, List<Long>>()

    fun viewerPhotos(screen: Screen.Viewer, now: () -> List<Long>): List<Long> {
        viewerPhotos.keys.retainAll(stack.toSet())
        return viewerPhotos.getOrPut(screen, now)
    }

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

    /** An event's photo screen keeps its place by the event's id, so editing the event does not reset it. */
    fun eventStateKey(event: Event): Source = Source.Album("event:${event.id}", event.id)

    fun eventGridState(event: Event) = gridState(eventStateKey(event))

    fun refresh(delayMs: Long = 0, manual: Boolean = false) {
        if (manual) refreshing.value = true
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(delayMs)
            val local = withContext(Dispatchers.IO) { loadMedia() }
            val fromCloud = if (prefs.showCloud.value) withContext(Dispatchers.IO) { cloud.asMediaItems(local) } else emptyList()
            val items = if (fromCloud.isEmpty()) local else (local + fromCloud).sortedByDescending { it.date }
            val before = _media.value?.associateBy { it.id }.orEmpty()
            ImageCache.forget(items.filter { before[it.id]?.let { old -> old.modified != it.modified } == true }.map { it.uri })
            _media.value = items
            prefs.migrateKeys(local)
            if (prefs.collectAuto(local) > 0) UploadWorker.schedule(getApplication())
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
        sendSoon()
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
        sendSoon()
    }

    fun sendNow() = UploadWorker.sendNow(getApplication())

    /**
     * Only when the user asks (Nastavení → Najít výlety): reads where camera photos were taken
     * (once, cached) and proposes trips that are not events yet.
     */
    /** What the last trip search found, shown under its button. */
    val tripResult = MutableStateFlow<String?>(null)

    /** Trips named by the AI so far / to name, while it works. */
    val tripNaming = MutableStateFlow<Pair<Int, Int>?>(null)

    @VisibleForTesting
    var nameTrip: suspend (List<MediaItem>) -> String? = { TripNamer.name(getApplication(), it) }

    fun findTrips() {
        if (prefs.tripSearch.value != null) return
        prefs.tripSearch.value = 0 to 0
        tripResult.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val camera = loadMedia().filter { it.isCameraShot }
                val cache = SpotCache(app)
                cache.readMissing(camera) { done, total -> prefs.tripSearch.value = done to total }
                val trips = nameByAi(proposeTrips(camera, cache), camera)
                prefs.setTrips(trips)
                tripResult.value = when {
                    camera.isEmpty() -> "Žádné fotky z fotoaparátu."
                    trips.isEmpty() -> "Žádné nové výlety: nikde výrazně víc fotek než obvykle (dny v událostech se přeskakují)."
                    else -> null
                }
            } catch (e: Exception) {
                tripResult.value = "Hledání se nepovedlo (${e.javaClass.simpleName})."
            } finally {
                prefs.tripSearch.value = null
            }
        }
    }

    /** Trips known only by their dates get a name from the AI on the phone, when it is there. */
    private suspend fun nameByAi(trips: List<TripProposal>, camera: List<MediaItem>): List<TripProposal> {
        val total = trips.count { it.datedName }
        var done = 0
        return trips.map { trip ->
            if (!trip.datedName) return@map trip
            tripNaming.value = done++ to total
            val name = nameTrip(camera.filter { it.day in trip.startDay..trip.endDay })
            if (name == null) trip else trip.copy(name = name, note = "${trip.note} · název navrhla AI v telefonu")
        }.also { tripNaming.value = null }
    }

    /**
     * Best source first: places (from the photos or the Timeline), then time zones,
     * then days with unusually many photos. Days already in a trip or an event are not proposed again.
     */
    private fun proposeTrips(camera: List<MediaItem>, cache: SpotCache): List<TripProposal> {
        val byPlace = TripFinder.find(camera.mapNotNull(cache::spot), Places.get(getApplication()))
        val covered = { trips: List<TripProposal> -> trips.flatMap { (it.startDay..it.endDay).toList() }.toSet() }
        val eventDays = prefs.events.value.flatMap { (it.startDay..it.endDay).toList() }.toSet()

        val zones = camera.mapNotNull { item -> cache.offset(item)?.let { item.day to it } }.toMap()
        val byZone = TripFinder.byTimeZone(zones, covered(byPlace))

        // Many more photos than usual: extends trips found by place or zone, and finds the rest.
        val perDay = camera.groupingBy { it.day }.eachCount()
        val ratio = TripFinder.busyness(perDay)
        val known = camera.filter { cache.spot(it) != null || cache.offset(it) != null }.map { it.day }.toSet()
        val located = (byPlace + byZone).map { TripFinder.extendByBusyDays(it, perDay, ratio, known) }
        val byBurst = TripFinder.byBursts(perDay, covered(located))

        return (located + byBurst)
            .filter { trip -> prefs.events.value.none { it.overlaps(trip.startDay, trip.endDay) } }
            .filter { trip -> (trip.startDay..trip.endDay).none { it in eventDays } }
            .map { trip -> trip.copy(photos = camera.count { it.day in trip.startDay..trip.endDay }) }
            .sortedByDescending { it.startDay }
    }

    /** Fills places of photos without GPS from a Timeline export, then searches again. Returns how many got one. */
    suspend fun importTimeline(uri: android.net.Uri): Int = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val timeline = app.contentResolver.openInputStream(uri)!!.bufferedReader().use(Timeline::read)
        val filled = SpotCache(app).fillFrom(timeline, loadMedia().filter { it.isCameraShot })
        findTrips()
        filled
    }

    /** Photos that can go from the phone because they are, checked just now, in Google Photos. */
    data class FreeUp(
        val photos: List<MediaItem>,
        val bytes: Long,
        val perAccount: Map<String, Int>,
        /** Accounts that first have to allow the check. */
        val needsLogin: List<String>,
        /** Sent, but not provably the same file (changed since, or sent by an older version). */
        val unsure: Int,
    )

    val freeUp = MutableStateFlow<FreeUp?>(null)
    val freeUpChecking = MutableStateFlow(false)
    val freeUpError = MutableStateFlow<String?>(null)

    /**
     * Only photos this app uploaded qualify: same file size as when sent, and Google confirms right now
     * that the item is still in the account under the same file name. Anything unsure stays on the phone.
     */
    fun checkFreeUp(keepFavorites: Boolean) {
        if (freeUpChecking.value) return
        freeUpChecking.value = true
        freeUpError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val camera = loadMedia().filter { it.isCameraShot && !(keepFavorites && it.isFavorite) }
                val candidates = freeUpCandidates(camera, prefs, keepFavorites)
                val verified = mutableListOf<Pair<MediaItem, String>>()
                val needsLogin = mutableListOf<String>()
                for ((account, group) in candidates.groupBy { it.second.account }) {
                    val token = GoogleAuth.silentToken(app, account, GoogleAuth.READ)
                    if (token == null) {
                        needsLogin += account
                        continue
                    }
                    val there = PhotosApi(app, token).existing(group.map { it.second.mediaItemId })
                    verified += confirmedInCloud(group, there).map { it to account }
                }
                val photos = verified.map { it.first }.distinctBy { it.id }
                freeUp.value = FreeUp(
                    photos = photos,
                    bytes = photos.sumOf { it.size },
                    perAccount = verified.groupingBy { it.second }.eachCount(),
                    needsLogin = needsLogin,
                    unsure = camera.count { prefs.wasSent(it.key) } - candidates.map { it.first.id }.distinct().size,
                )
            } catch (e: java.io.IOException) {
                freeUpError.value = "Ověření u Googlu se nepovedlo (${e.message?.take(80)}). Bez internetu se nic nesmaže."
            } finally {
                freeUpChecking.value = false
            }
        }
    }

    /**
     * Asks Google again, right before deleting, which of these photos are in the cloud now.
     * Only those may go; throws when Google cannot be asked (then nothing is deleted).
     */
    suspend fun confirmNow(photos: List<MediaItem>): List<MediaItem> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        freeUpCandidates(photos, prefs, keepFavorites = false).groupBy { it.second.account }.flatMap { (account, group) ->
            val token = GoogleAuth.silentToken(app, account, GoogleAuth.READ) ?: return@flatMap emptyList()
            confirmedInCloud(group, PhotosApi(app, token).existing(group.map { it.second.mediaItemId }))
        }
    }

    fun onFreed() {
        freeUp.value = null
        refresh()
    }

    /** Confirmed trips become events, and if wanted albums in the app (links only, nothing is uploaded). */
    fun createTrips(ids: Set<String>, withAlbums: Boolean) {
        val camera = media.value.orEmpty().filter { it.isCameraShot }
        prefs.trips.value.filter { it.id in ids }.forEachIndexed { index, trip ->
            val color = EVENT_COLORS[(prefs.events.value.size + index) % EVENT_COLORS.size]
            saveEvent(Event(name = trip.name, color = color, startDay = trip.startDay, endDay = trip.endDay))
            if (withAlbums) prefs.createAlbum(trip.name, camera.filter { it.day in trip.startDay..trip.endDay })
        }
        prefs.removeTrips(ids)
    }

    val cloud by lazy { CloudLibrary(getApplication()) }

    /** Where the Google Photos sync is: picking, downloading previews, done or stopped by a problem. */
    data class CloudSync(
        val account: String,
        val round: Int = 1,
        val added: Int = 0,
        val downloading: Int = 0,
        val done: Boolean = false,
        val error: String? = null,
    )

    val cloudSync = MutableStateFlow<CloudSync?>(null)
    private var cloudJob: Job? = null

    @VisibleForTesting
    var cloudToken: suspend (String) -> String? = { GoogleAuth.silentToken(getApplication(), it, GoogleAuth.PICKER) }

    @VisibleForTesting
    var openPicker: (String) -> Unit = { uri ->
        GooglePhotos.openLink(getApplication(), uri)
    }

    /**
     * Picks photos in Google Photos again and again: each round keeps the new ones and opens the picker
     * once more, until a round brings nothing new. Google cannot hide what was picked before, so the
     * app skips those itself. Wi-Fi only; on paid data it stops and nothing is downloaded.
     */
    fun startCloudSync(account: String) {
        if (cloudJob?.isActive == true) return
        cloudSync.value = CloudSync(account)
        cloudJob = viewModelScope.launch(Dispatchers.IO) {
            var session: String? = null
            val api = try {
                PickerApi(cloudToken(account) ?: throw java.io.IOException("Google nepovolil přístup"))
            } catch (e: java.io.IOException) {
                cloudSync.value = cloudSync.value?.copy(error = e.message)
                return@launch
            }
            try {
                while (true) {
                    if (!onWifi(getApplication())) throw NotOnWifi()
                    val picking = api.create().also { session = it.id }
                    withContext(Dispatchers.Main) { openPicker(picking.uri + "/autoclose") }
                    val until = System.currentTimeMillis() + picking.timeoutMillis
                    while (!api.isPicked(picking.id)) {
                        if (System.currentTimeMillis() > until) throw java.io.IOException("Výběr vypršel")
                        delay(picking.pollMillis)
                    }
                    val fresh = api.items(picking.id).filterNot { cloud.has(it.id) }.distinctBy { it.id }
                    cloudSync.value = cloudSync.value?.copy(downloading = fresh.size)
                    download(api, fresh)
                    api.delete(picking.id)
                    session = null
                    refresh()
                    val state = cloudSync.value ?: return@launch
                    if (fresh.isEmpty()) {
                        cloudSync.value = state.copy(done = true, downloading = 0)
                        return@launch
                    }
                    cloudSync.value = state.copy(round = state.round + 1, added = state.added + fresh.size, downloading = 0)
                }
            } catch (e: java.io.IOException) {
                cloudSync.value = cloudSync.value?.copy(error = e.message?.take(120), downloading = 0)
            } finally {
                session?.let(api::delete)
                refresh()
            }
        }
    }

    /** Previews are fetched a few at a time; each is saved at once, so a stopped sync keeps what it got. */
    private suspend fun download(api: PickerApi, photos: List<PickerApi.Picked>) = coroutineScope {
        photos.chunked(8).forEach { chunk ->
            if (!onWifi(getApplication())) throw NotOnWifi()
            chunk.map { picked ->
                async { picked to CloudLibrary.shrink(api.preview(picked)) }
            }.awaitAll().forEach { (picked, preview) ->
                cloud.add(CloudPhoto(picked.id, picked.created, picked.name, picked.mime), preview)
            }
        }
    }

    fun stopCloudSync() {
        cloudJob?.cancel()
        cloudSync.value = null
    }

    fun setShowCloud(show: Boolean) {
        prefs.setShowCloud(show)
        refresh()
    }

    /** New photos in the queue: start a fresh run (on Wi-Fi) unless one is uploading right now. */
    private fun sendSoon() = UploadWorker.schedule(getApplication(), restart = prefs.uploading.value == null)

    fun setWarmup(enabled: Boolean) {
        prefs.setWarmup(enabled)
        WarmupWorker.schedule(getApplication(), enabled)
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
    }
}

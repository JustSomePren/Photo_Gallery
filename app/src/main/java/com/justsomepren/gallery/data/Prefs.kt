package com.justsomepren.gallery.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A photo in an album, found by [key]; the id is kept only for reference. */
data class AlbumRef(val id: Long, val key: String)

data class CustomAlbum(val id: String, val name: String, val refs: List<AlbumRef>)

private fun MediaItem.ref() = AlbumRef(id, key)

/** A named shortcut to an app that uploads photos, e.g. "Shared cloud – Drive". */
data class UploadTarget(val name: String, val packageName: String, val activityName: String)

/** A photo waiting to be uploaded to a Google account, optionally into an album. */
data class QueueItem(
    val key: String,
    val uri: String,
    val name: String,
    val mime: String,
    val account: String,
    val album: String?,
) {
    val sentKey get() = "$account|$key"

    companion object {
        fun of(item: MediaItem, account: String, album: String?) =
            QueueItem(item.key, item.uri.toString(), item.name, item.mime, account, album?.ifBlank { null })
    }
}

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    /** An append-only file, so marking photos as sent stays cheap. */
    private val sentFile = File(context.filesDir, "sent.txt")
    private val sent = sentFile.takeIf { it.exists() }?.readLines()?.toMutableSet() ?: mutableSetOf()

    private val albumList = Stored("albums", ::albumToJson, ::albumFromJson)
    private val targetList = Stored("targets", ::targetToJson, ::targetFromJson)
    private val eventList = Stored("events", ::eventToJson, ::eventFromJson)
    private val accountList = Stored("accounts", { JSONObject().put("email", it) }, { it.getString("email") })
    private val queueList = Stored("queue", ::queueToJson, ::queueFromJson)
    private val failedList = Stored("failed", ::queueToJson, ::queueFromJson)

    val albums = albumList.flow
    val targets = targetList.flow
    val events = eventList.flow
    val accounts = accountList.flow
    val queue = queueList.flow
    val failed = failedList.flow

    val threshold = MutableStateFlow(sp.getInt("threshold", 70))
    val warmup = MutableStateFlow(sp.getBoolean("warmup", true))
    val calendarView = MutableStateFlow(sp.getBoolean("calendar", false))
    val mobileData = MutableStateFlow(sp.getBoolean("mobileData", false))

    /** Accounts whose Google login has to be confirmed again. */
    val accountProblems = MutableStateFlow(emptySet<String>())

    /** Done / total while the upload is running, otherwise null. */
    val uploading = MutableStateFlow<Pair<Int, Int>?>(null)

    val hasTargets get() = sp.contains("targets")

    fun setThreshold(value: Int) = setInt(threshold, "threshold", value)
    fun setWarmup(value: Boolean) = setBoolean(warmup, "warmup", value)
    fun setCalendarView(value: Boolean) = setBoolean(calendarView, "calendar", value)
    fun setMobileData(value: Boolean) = setBoolean(mobileData, "mobileData", value)

    // ---- Albums ----

    fun createAlbum(name: String, items: List<MediaItem> = emptyList()) = albumList.update {
        it + CustomAlbum(UUID.randomUUID().toString(), name, items.map { item -> item.ref() })
    }

    fun deleteAlbum(id: String) = albumList.update { list -> list.filterNot { it.id == id } }

    fun addToAlbum(id: String, items: List<MediaItem>) = changeRefs(id) { refs ->
        (refs + items.map { it.ref() }).distinctBy { it.key }
    }

    fun removeFromAlbum(id: String, items: List<MediaItem>) = changeRefs(id) { refs ->
        val keys = items.map { it.key }.toSet()
        refs.filterNot { it.key in keys }
    }

    /** Albums from the first version used "name|date added" for photos without a capture date. */
    fun migrateKeys(media: List<MediaItem>) {
        val byId = media.associateBy { it.id }
        val migrated = albums.value.map { album ->
            album.copy(refs = album.refs.map { ref ->
                val item = byId[ref.id]
                if (item != null && ref.key != item.key && ref.key == "${item.name}|${item.date}") item.ref() else ref
            })
        }
        if (migrated != albums.value) albumList.update { migrated }
    }

    fun exportAlbums(): String = JSONArray(albums.value.map(::albumToJson)).toString(2)

    /** Returns how many albums were imported. Albums with the same id are replaced. */
    fun importAlbums(json: String): Int {
        val array = JSONArray(json)
        val imported = List(array.length()) { albumFromJson(array.getJSONObject(it)) }
        albumList.update { current -> current.filterNot { old -> imported.any { it.id == old.id } } + imported }
        return imported.size
    }

    /** Everything the app keeps, including which photos were already sent, so nothing goes twice. */
    fun exportAll(): String = Backup.export(sp, listOf(sentFile))

    /**
     * Accepts a full backup or an older file with albums only.
     * Returns true for a full backup; the app then has to start again to read it.
     */
    fun restore(json: String): Boolean {
        if (json.trimStart().startsWith("[")) {
            importAlbums(json)
            return false
        }
        Backup.restore(json, sp, listOf(sentFile))
        return true
    }

    private fun changeRefs(id: String, change: (List<AlbumRef>) -> List<AlbumRef>) = albumList.update { list ->
        list.map { if (it.id == id) it.copy(refs = change(it.refs)) else it }
    }

    // ---- Share targets ----

    fun addTargets(new: List<UploadTarget>) = targetList.update { it + new }

    fun deleteTarget(target: UploadTarget) = targetList.update { it - target }

    // ---- Events ----

    fun saveEvent(event: Event) = eventList.update { list ->
        if (list.any { it.id == event.id }) list.map { if (it.id == event.id) event else it } else list + event
    }

    fun deleteEvent(event: Event) = eventList.update { list -> list.filterNot { it.id == event.id } }

    /** Replaces the vacations from the Kalendář app between [from] and [to] (epoch days). */
    fun syncLinked(from: Long, to: Long, linked: List<Event>) {
        val kept = events.value.filterNot { it.isLinked && it.overlaps(from, to) }
        val updated = (kept + linked).distinctBy { it.id }
        if (updated != events.value) eventList.update { updated }
    }

    // ---- Accounts ----

    fun addAccount(email: String) {
        accountList.update { (it + email).distinct() }
        accountProblems.value -= email
    }

    fun removeAccount(email: String) {
        accountList.update { it - email }
        queueList.update { list -> list.filterNot { it.account == email } }
    }

    // ---- Upload queue ----

    fun isSent(item: QueueItem) = item.sentKey in sent

    /** Adds what is not queued or sent yet; returns how many were added. */
    fun enqueue(items: List<QueueItem>): Int {
        val queued = queue.value.map { it.sentKey }.toSet()
        val new = items.distinctBy { it.sentKey }.filter { it.sentKey !in queued && !isSent(it) }
        if (new.isNotEmpty()) queueList.update { it + new }
        return new.size
    }

    /** Queues photos of active auto-send events, from their first to their last day. */
    fun collectAuto(media: List<MediaItem>): Int {
        val rules = events.value.filter { it.autoSend && it.account != null }
        if (rules.isEmpty()) return 0
        return enqueue(media.flatMap { item ->
            rules.filter { it.covers(item.day) && (!it.isLinked || item.isCamera) }.map { QueueItem.of(item, it.account!!, it.album) }
        })
    }

    fun markSent(items: List<QueueItem>) {
        val keys = items.map { it.sentKey }
        sent += keys
        sentFile.appendText(keys.joinToString("") { "$it\n" })
        removeFromQueue(items)
    }

    fun markFailed(items: List<QueueItem>) {
        failedList.update { (it + items).distinctBy { item -> item.sentKey } }
        removeFromQueue(items)
    }

    fun retryFailed() {
        val items = failed.value
        failedList.update { emptyList() }
        enqueue(items)
    }

    fun clearQueue() = queueList.update { emptyList() }

    fun removeFromQueue(items: List<QueueItem>) {
        val keys = items.map { it.sentKey }.toSet()
        queueList.update { list -> list.filterNot { it.sentKey in keys } }
    }

    /** Google Photos album ids the app created, per account and title. */
    fun albumId(account: String, title: String): String? = sp.getString("albumId|$account|$title", null)

    fun setAlbumId(account: String, title: String, id: String?) =
        sp.edit { if (id == null) remove("albumId|$account|$title") else putString("albumId|$account|$title", id) }

    // ---- Storage helpers ----

    private fun setInt(flow: MutableStateFlow<Int>, key: String, value: Int) {
        flow.value = value
        sp.edit { putInt(key, value) }
    }

    private fun setBoolean(flow: MutableStateFlow<Boolean>, key: String, value: Boolean) {
        flow.value = value
        sp.edit { putBoolean(key, value) }
    }

    /** A list kept in memory as a flow and saved as JSON. */
    private inner class Stored<T>(
        private val key: String,
        private val toJson: (T) -> JSONObject,
        fromJson: (JSONObject) -> T,
    ) {
        val flow = MutableStateFlow(read(fromJson))

        @Synchronized
        fun update(change: (List<T>) -> List<T>) {
            flow.value = change(flow.value)
            sp.edit { putString(key, JSONArray(flow.value.map(toJson)).toString()) }
        }

        private fun read(fromJson: (JSONObject) -> T): List<T> {
            val array = JSONArray(sp.getString(key, null) ?: return emptyList())
            return List(array.length()) { fromJson(array.getJSONObject(it)) }
        }
    }

    private fun albumToJson(a: CustomAlbum) = JSONObject()
        .put("id", a.id)
        .put("name", a.name)
        .put("items", JSONArray(a.refs.map { JSONObject().put("id", it.id).put("key", it.key) }))

    private fun albumFromJson(o: JSONObject): CustomAlbum {
        val items = o.getJSONArray("items")
        val refs = List(items.length()) {
            val item = items.getJSONObject(it)
            AlbumRef(item.getLong("id"), item.getString("key"))
        }
        return CustomAlbum(o.getString("id"), o.getString("name"), refs)
    }

    private fun targetToJson(t: UploadTarget) = JSONObject()
        .put("name", t.name).put("pkg", t.packageName).put("activity", t.activityName)

    private fun targetFromJson(o: JSONObject) =
        UploadTarget(o.getString("name"), o.getString("pkg"), o.getString("activity"))

    private fun eventToJson(e: Event) = JSONObject()
        .put("id", e.id).put("name", e.name).put("color", e.color)
        .put("start", e.startDay).put("end", e.endDay)
        .put("auto", e.autoSend).put("account", e.account ?: "").put("album", e.album ?: "")
        .apply { if (e.linkedId != null) put("linkedId", e.linkedId).put("linkedBegin", e.linkedBegin) }

    private fun eventFromJson(o: JSONObject) = Event(
        id = o.getString("id"),
        name = o.getString("name"),
        color = o.getInt("color"),
        startDay = o.getLong("start"),
        endDay = o.getLong("end"),
        autoSend = o.getBoolean("auto"),
        account = o.getString("account").ifBlank { null },
        album = o.getString("album").ifBlank { null },
        linkedId = if (o.has("linkedId")) o.getLong("linkedId") else null,
        linkedBegin = if (o.has("linkedBegin")) o.getLong("linkedBegin") else null,
    )

    private fun queueToJson(q: QueueItem) = JSONObject()
        .put("key", q.key).put("uri", q.uri).put("name", q.name).put("mime", q.mime)
        .put("account", q.account).put("album", q.album ?: "")

    private fun queueFromJson(o: JSONObject) = QueueItem(
        key = o.getString("key"),
        uri = o.getString("uri"),
        name = o.getString("name"),
        mime = o.getString("mime"),
        account = o.getString("account"),
        album = o.getString("album").ifBlank { null },
    )

    companion object {
        @Volatile
        private var instance: Prefs? = null

        @VisibleForTesting
        fun reset() {
            instance = null
        }

        /** One shared instance, so the screens and the upload worker see the same data. */
        fun get(context: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(context.applicationContext).also { instance = it }
        }
    }
}

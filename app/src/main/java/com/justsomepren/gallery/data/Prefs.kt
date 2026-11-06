package com.justsomepren.gallery.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A photo in an album: found by id, or by name and date when the id changed. */
data class AlbumRef(val id: Long, val key: String)

data class CustomAlbum(val id: String, val name: String, val refs: List<AlbumRef>)

private fun MediaItem.ref() = AlbumRef(id, key)

/** A named shortcut to an app that uploads photos, e.g. "Shared cloud – Drive". */
data class UploadTarget(val name: String, val packageName: String, val activityName: String)

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    val albums = MutableStateFlow(readList("albums", ::albumFromJson))
    val targets = MutableStateFlow(readList("targets", ::targetFromJson))
    val threshold = MutableStateFlow(sp.getInt("threshold", 70))
    val warmup = MutableStateFlow(sp.getBoolean("warmup", true))

    val hasTargets get() = sp.contains("targets")

    fun setThreshold(value: Int) {
        threshold.value = value
        sp.edit { putInt("threshold", value) }
    }

    fun setWarmup(value: Boolean) {
        warmup.value = value
        sp.edit { putBoolean("warmup", value) }
    }

    fun createAlbum(name: String, items: List<MediaItem> = emptyList()) = updateAlbums {
        it + CustomAlbum(UUID.randomUUID().toString(), name, items.map { item -> item.ref() })
    }

    fun deleteAlbum(id: String) = updateAlbums { list -> list.filterNot { it.id == id } }

    fun addToAlbum(id: String, items: List<MediaItem>) = changeRefs(id) { refs ->
        (refs + items.map { it.ref() }).distinctBy { it.key }
    }

    fun removeFromAlbum(id: String, items: List<MediaItem>) = changeRefs(id) { refs ->
        val ids = items.map { it.id }.toSet()
        val keys = items.map { it.key }.toSet()
        refs.filterNot { it.id in ids || it.key in keys }
    }

    /** Updates ids of photos that were reindexed, so albums keep working. */
    fun repairAlbums(media: List<MediaItem>) {
        val byKey = media.associateBy { it.key }
        val repaired = albums.value.map { album ->
            album.copy(refs = album.refs.map { ref -> byKey[ref.key]?.ref() ?: ref })
        }
        if (repaired != albums.value) updateAlbums { repaired }
    }

    fun exportAlbums(): String = JSONArray(albums.value.map(::albumToJson)).toString(2)

    /** Returns how many albums were imported. Albums with the same id are replaced. */
    fun importAlbums(json: String): Int {
        val array = JSONArray(json)
        val imported = List(array.length()) { albumFromJson(array.getJSONObject(it)) }
        updateAlbums { current -> current.filterNot { old -> imported.any { it.id == old.id } } + imported }
        return imported.size
    }

    private fun changeRefs(id: String, change: (List<AlbumRef>) -> List<AlbumRef>) = updateAlbums { list ->
        list.map { if (it.id == id) it.copy(refs = change(it.refs)) else it }
    }

    fun addTargets(new: List<UploadTarget>) = updateTargets { it + new }

    fun deleteTarget(target: UploadTarget) = updateTargets { it - target }

    private fun updateAlbums(change: (List<CustomAlbum>) -> List<CustomAlbum>) {
        albums.value = change(albums.value)
        write("albums", albums.value.map(::albumToJson))
    }

    private fun updateTargets(change: (List<UploadTarget>) -> List<UploadTarget>) {
        targets.value = change(targets.value)
        write("targets", targets.value.map(::targetToJson))
    }

    private fun write(key: String, items: List<JSONObject>) =
        sp.edit { putString(key, JSONArray(items).toString()) }

    private fun <T> readList(key: String, parse: (JSONObject) -> T): List<T> {
        val array = JSONArray(sp.getString(key, null) ?: return emptyList())
        return List(array.length()) { parse(array.getJSONObject(it)) }
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
}

package com.justsomepren.gallery.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CustomAlbum(val id: String, val name: String, val mediaIds: List<Long>)

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

    fun createAlbum(name: String, ids: List<Long> = emptyList()) = updateAlbums {
        it + CustomAlbum(UUID.randomUUID().toString(), name, ids)
    }

    fun deleteAlbum(id: String) = updateAlbums { list -> list.filterNot { it.id == id } }

    fun addToAlbum(id: String, ids: List<Long>) = updateAlbums { list ->
        list.map { if (it.id == id) it.copy(mediaIds = (it.mediaIds + ids).distinct()) else it }
    }

    fun removeFromAlbum(id: String, ids: List<Long>) = updateAlbums { list ->
        list.map { if (it.id == id) it.copy(mediaIds = it.mediaIds - ids.toSet()) else it }
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
        .put("id", a.id).put("name", a.name).put("ids", JSONArray(a.mediaIds))

    private fun albumFromJson(o: JSONObject): CustomAlbum {
        val ids = o.getJSONArray("ids")
        return CustomAlbum(o.getString("id"), o.getString("name"), List(ids.length()) { ids.getLong(it) })
    }

    private fun targetToJson(t: UploadTarget) = JSONObject()
        .put("name", t.name).put("pkg", t.packageName).put("activity", t.activityName)

    private fun targetFromJson(o: JSONObject) =
        UploadTarget(o.getString("name"), o.getString("pkg"), o.getString("activity"))
}

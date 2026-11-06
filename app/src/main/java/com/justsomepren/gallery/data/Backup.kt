package com.justsomepren.gallery.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** All settings of the app in one JSON file, e.g. before reinstalling or for a new phone. */
object Backup {

    fun export(prefs: SharedPreferences, files: List<File> = emptyList()): String {
        val values = JSONObject()
        prefs.all.forEach { (key, value) ->
            val item = when (value) {
                is Boolean -> JSONObject().put("b", value)
                is Int -> JSONObject().put("i", value)
                is Long -> JSONObject().put("l", value)
                is Float -> JSONObject().put("f", value.toDouble())
                is String -> JSONObject().put("s", value)
                is Set<*> -> JSONObject().put("set", JSONArray(value.toList()))
                else -> null
            }
            if (item != null) values.put(key, item)
        }
        val texts = JSONObject()
        files.filter { it.exists() }.forEach { texts.put(it.name, it.readText()) }
        return JSONObject().put("backup", 1).put("prefs", values).put("files", texts).toString(2)
    }

    /** Replaces everything. Only the files listed in [files] are written, whatever the backup contains. */
    fun restore(json: String, prefs: SharedPreferences, files: List<File> = emptyList()) {
        val root = JSONObject(json)
        val values = root.getJSONObject("prefs")
        val edit = prefs.edit().clear()
        values.keys().forEach { key ->
            val item = values.getJSONObject(key)
            when {
                item.has("b") -> edit.putBoolean(key, item.getBoolean("b"))
                item.has("i") -> edit.putInt(key, item.getInt("i"))
                item.has("l") -> edit.putLong(key, item.getLong("l"))
                item.has("f") -> edit.putFloat(key, item.getDouble("f").toFloat())
                item.has("s") -> edit.putString(key, item.getString("s"))
                item.has("set") -> {
                    val array = item.getJSONArray("set")
                    edit.putStringSet(key, List(array.length()) { array.getString(it) }.toSet())
                }
            }
        }
        edit.commit()
        val texts = root.optJSONObject("files") ?: JSONObject()
        files.forEach { file -> if (texts.has(file.name)) file.writeText(texts.getString(file.name)) else file.delete() }
    }
}

package com.justsomepren.gallery.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Uri
import androidx.annotation.VisibleForTesting
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

/** A photo that is only in Google Photos; the app keeps its name, date and a small preview. */
data class CloudPhoto(val id: String, val created: Long, val name: String, val mime: String)

/**
 * Previews of photos picked in Google Photos (Picker), kept in one folder of the app so they can be
 * browsed without the originals. Nothing here is ever sent anywhere or deleted from the phone's photos.
 */
class CloudLibrary(context: Context) {
    private val dir = File(context.filesDir, "cloud").apply { mkdirs() }
    private val index = File(dir, "index.tsv")
    private val photos: MutableMap<String, CloudPhoto> = read()

    val count get() = photos.size

    fun has(id: String) = id in photos

    fun sizeBytes(): Long = dir.listFiles().orEmpty().sumOf { it.length() }

    /** The oldest photo so far: where the next pick should continue. */
    fun oldest(): Long? = photos.values.minOfOrNull { it.created }

    @Synchronized
    fun add(photo: CloudPhoto, preview: ByteArray) {
        previewFile(photo.id).writeBytes(preview)
        index.appendText(line(photo))
        photos[photo.id] = photo
    }

    /** Deletes every preview; the photos in Google Photos stay as they are. */
    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        photos.clear()
    }

    fun ids(): Set<String> = photos.keys.toSet()

    /** Photos deleted in Google Photos lose their preview too. */
    @Synchronized
    fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        ids.forEach {
            photos.remove(it)
            previewFile(it).delete()
        }
        index.writeText(photos.values.joinToString("", transform = ::line))
    }

    /**
     * Previews an older version saved far too big: made small, or dropped when they are not pictures
     * (the next reading of the library loads them again). True when something changed.
     */
    @Synchronized
    fun repair(): Boolean {
        val big = photos.keys.filter { previewFile(it).length() > TOO_BIG }
        remove(big.filter { id ->
            val file = previewFile(id)
            val small = runCatching { shrink(file.readBytes()) }.getOrNull()
            small?.let(file::writeBytes) == null
        })
        return big.isNotEmpty()
    }

    private fun line(photo: CloudPhoto) = "${photo.id}\t${photo.created}\t${photo.name}\t${photo.mime}\n"

    /** The previews as gallery items, leaving out photos that are on the phone as well. */
    fun asMediaItems(local: List<MediaItem>): List<MediaItem> {
        val onPhone = local.map { it.name to it.date / 60_000 }.toSet()
        return photos.values.filter { (it.name to it.created / 60_000) !in onPhone }.map { photo ->
            MediaItem(
                id = idFor(photo.id),
                uri = Uri.fromFile(previewFile(photo.id)),
                mime = photo.mime,
                isVideo = photo.mime.startsWith("video/"),
                date = photo.created,
                dateTaken = photo.created,
                bucketId = CLOUD_BUCKET,
                bucketName = "Google Fotky (náhledy)",
                isFavorite = false,
                name = photo.name,
                width = 0,
                height = 0,
                size = 0,
                relativePath = "",
                cloud = true,
                // Known only for photos read from the photos.google.com page
                link = photo.id.takeIf { it.startsWith("web:") }?.let { "https://photos.google.com/photo/" + it.removePrefix("web:") },
            )
        }
    }

    private fun previewFile(id: String) = File(dir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".jpg")

    private fun read(): MutableMap<String, CloudPhoto> {
        if (!index.exists()) return mutableMapOf()
        return index.readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 4) null else p[0] to CloudPhoto(p[0], p[1].toLong(), p[2], p[3])
        }.toMap().toMutableMap()
    }

    companion object {
        const val CLOUD_BUCKET = -1L

        /** A stable negative id, so cloud previews never clash with the phone's photos. */
        fun idFor(cloudId: String): Long = -(UUID.nameUUIDFromBytes(cloudId.toByteArray()).mostSignificantBits and Long.MAX_VALUE) - 1

        private const val PREVIEW = 400
        private const val TOO_BIG = 150_000

        /** A small JPEG ([PREVIEW] px on the shorter side at most); null when the bytes are not a picture. */
        fun shrink(bytes: ByteArray): ByteArray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW) sample *= 2
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val scale = PREVIEW.toFloat() / minOf(bitmap.width, bitmap.height)
            val small = if (scale < 1) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
            return ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
        }
    }
}

/** Cloud previews are fetched only on Wi-Fi (or another network that is not paid per use). */
fun onWifi(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    return cm.activeNetwork != null && !cm.isActiveNetworkMetered
}

/**
 * The Google Photos Picker: the user picks photos in Google Photos, the app then reads what was picked.
 * Google does not let apps browse the whole library, nor hide photos picked before.
 */
class PickerApi(private val token: String) {
    data class Session(val id: String, val uri: String, val pollMillis: Long, val timeoutMillis: Long)
    data class Picked(val id: String, val created: Long, val name: String, val mime: String, val baseUrl: String)

    fun create(): Session {
        val json = JSONObject(call("$baseUrl/sessions", "POST", JSONObject().put("pickingConfig", JSONObject().put("maxItemCount", "2000"))))
        val polling = json.optJSONObject("pollingConfig")
        return Session(
            id = json.getString("id"),
            uri = json.getString("pickerUri"),
            pollMillis = seconds(polling?.optString("pollInterval")) ?: 5_000,
            timeoutMillis = seconds(polling?.optString("timeoutIn")) ?: 30 * 60_000,
        )
    }

    fun isPicked(session: String): Boolean = JSONObject(call("$baseUrl/sessions/$session", "GET")).optBoolean("mediaItemsSet")

    fun items(session: String): List<Picked> {
        val all = mutableListOf<Picked>()
        var page: String? = null
        do {
            val url = "$baseUrl/mediaItems?sessionId=$session&pageSize=100" + (page?.let { "&pageToken=$it" } ?: "")
            val json = JSONObject(call(url, "GET"))
            val list = json.optJSONArray("mediaItems")
            for (i in 0 until (list?.length() ?: 0)) {
                val item = list!!.getJSONObject(i)
                val file = item.getJSONObject("mediaFile")
                all += Picked(
                    id = item.getString("id"),
                    created = runCatching { Instant.parse(item.getString("createTime")).toEpochMilli() }.getOrDefault(0),
                    name = file.optString("filename"),
                    mime = file.optString("mimeType", "image/jpeg"),
                    baseUrl = file.getString("baseUrl"),
                )
            }
            page = json.optString("nextPageToken").ifEmpty { null }
        } while (page != null)
        return all
    }

    fun delete(session: String) {
        runCatching { call("$baseUrl/sessions/$session", "DELETE") }
    }

    /** A small preview (at most 300 px); a video gives its first frame. */
    fun preview(picked: Picked): ByteArray {
        val connection = (URL(picked.baseUrl + "=w300-h300").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        if (connection.responseCode !in 200..299) throw HttpException(connection.responseCode, "preview")
        return connection.inputStream.use { it.readBytes() }
    }

    private fun call(url: String, method: String, body: JSONObject? = null): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
        }
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            throw HttpException(code, connection.errorStream?.bufferedReader()?.readText().orEmpty().take(300))
        }
        return connection.inputStream.bufferedReader().use { it.readText() }.ifEmpty { "{}" }
    }

    /** "5s", "1800s" → milliseconds. */
    private fun seconds(text: String?): Long? = text?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * 1000).toLong() }

    companion object {
        @VisibleForTesting
        var baseUrl = "https://photospicker.googleapis.com/v1"
    }
}

/** Thrown when a step needs Wi-Fi and the phone is on paid data. */
class NotOnWifi : IOException("Jen na Wi-Fi")

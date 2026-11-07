package com.justsomepren.gallery.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Photos this app sent to Google Photos. Google lets the app read back only those.
 * A small preview of each is kept in the phone, so the calendar shows them even offline,
 * e.g. after "Free up space" removed the original from the phone.
 */
object CloudPhotos {
    private const val PREVIEW = "=w512-h512"

    @Volatile
    private var cached: List<MediaItem>? = null

    /** Grows after each download, so the screens show the new photos. */
    val version = MutableStateFlow(0)

    private fun dir(context: Context) = File(context.filesDir, "cloud").apply { mkdirs() }

    private fun index(context: Context) = File(dir(context), "index.json")

    private fun preview(context: Context, googleId: String) = File(dir(context), "${googleId.hashCode().toUInt()}.jpg")

    fun items(context: Context): List<MediaItem> = cached ?: read(context).also { cached = it }

    @VisibleForTesting
    fun forget() {
        cached = null
    }

    /** The phone's photos plus those only in the cloud; a photo still in the phone shows once. */
    fun withCloud(context: Context, local: List<MediaItem>): List<MediaItem> {
        if (!Prefs.get(context).showCloud.value) return local
        val inPhone = local.map { it.name }.toHashSet()
        val extra = items(context).filter { it.name !in inPhone }
        return if (extra.isEmpty()) local else local + extra
    }

    fun previewFile(context: Context, id: Long): File? = items(context).find { it.id == id }?.uri?.path?.let(::File)

    /** Reads the list from every connected account and downloads previews that are missing. */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val prefs = Prefs.get(context)
        val found = mutableListOf<JSONObject>()
        // An account whose login lapsed keeps its previews until it can be read again
        var complete = true
        for (account in prefs.accounts.value) {
            val token = GoogleAuth.silentToken(context, account)
            if (token == null) {
                complete = false
                continue
            }
            val api = PhotosApi(context, token)
            var page: String? = null
            do {
                val result = api.listOwn(page)
                val list = result.optJSONArray("mediaItems") ?: JSONArray()
                for (i in 0 until list.length()) {
                    val item = list.getJSONObject(i)
                    val file = preview(context, item.getString("id"))
                    if (!file.exists()) {
                        runCatching { api.download(item.getString("baseUrl") + PREVIEW, file) }.onFailure { file.delete() }
                    }
                    if (file.exists()) found += item
                }
                page = result.optString("nextPageToken").ifBlank { null }
            } while (page != null)
        }
        if (!complete) found += readIndex(context)
        // The same photo sent to two accounts shows once
        val entries = found.distinctBy { it.getString("filename") + it.optJSONObject("mediaMetadata")?.optString("creationTime") }
        if (complete) {
            val keep = entries.map { preview(context, it.getString("id")).name }.toSet() + "index.json"
            dir(context).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        }
        index(context).writeText(JSONArray(entries).toString())
        cached = null
        version.value++
    }

    private fun readIndex(context: Context): List<JSONObject> {
        val file = index(context)
        val array = runCatching { JSONArray(file.readText()) }.getOrNull() ?: return emptyList()
        return List(array.length()) { array.getJSONObject(it) }
    }

    private fun read(context: Context) = readIndex(context).mapNotNull { runCatching { toItem(context, it) }.getOrNull() }

    private fun toItem(context: Context, json: JSONObject): MediaItem {
        val googleId = json.getString("id")
        val meta = json.getJSONObject("mediaMetadata")
        val taken = Instant.parse(meta.getString("creationTime")).toEpochMilli()
        val mime = json.optString("mimeType", "image/jpeg")
        return MediaItem(
            // Negative, so it never meets an id of the phone's photos
            id = -(UUID.nameUUIDFromBytes(googleId.toByteArray()).mostSignificantBits and Long.MAX_VALUE) - 1,
            uri = Uri.fromFile(preview(context, googleId)),
            mime = mime,
            isVideo = mime.startsWith("video"),
            date = taken,
            dateTaken = taken,
            bucketId = -1,
            bucketName = "Google Fotky",
            isFavorite = false,
            name = json.getString("filename"),
            width = meta.optString("width").toIntOrNull() ?: 0,
            height = meta.optString("height").toIntOrNull() ?: 0,
            size = 0,
            // Only camera photos are ever sent, so they count like them
            relativePath = "DCIM/Camera/",
            cloudUrl = json.optString("productUrl"),
        )
    }
}

/** Downloads the previews only on Wi-Fi and above the battery threshold (or while charging). */
class CloudWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        if (!prefs.showCloud.value || prefs.accounts.value.isEmpty()) return Result.success()
        val metered = applicationContext.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        if (metered || applicationContext.belowBatteryThreshold(prefs.threshold.value)) return Result.success()
        return try {
            CloudPhotos.refresh(applicationContext)
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC = "cloud-periodic"
        private const val ONCE = "cloud-once"

        private val wifi = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Twice a day, plus once when the app is opened; the worker itself checks the battery threshold. */
        fun schedule(context: Context) {
            val work = WorkManager.getInstance(context)
            if (!Prefs.get(context).showCloud.value) {
                work.cancelUniqueWork(PERIODIC)
                return
            }
            val periodic = PeriodicWorkRequestBuilder<CloudWorker>(12, TimeUnit.HOURS).setConstraints(wifi).build()
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
            work.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<CloudWorker>().setConstraints(wifi).build())
        }
    }
}

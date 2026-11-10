package com.justsomepren.gallery.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
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
 * A small preview of each is kept in the phone, so they still show after "Free up space"
 * removed the original. Old photos picked by hand are in [CloudLibrary].
 */
object CloudPhotos {
    private const val PREVIEW = "=w512-h512"

    @Volatile
    private var cached: List<MediaItem>? = null

    /** Grows after each download, so the screens show the new photos. */
    val version = MutableStateFlow(0)

    // Not the folder of CloudLibrary: each side cleans only its own previews
    private fun dir(context: Context) = File(context.filesDir, "cloud-sent").apply { mkdirs() }

    private fun index(context: Context) = File(dir(context), "index.json")

    private fun preview(context: Context, googleId: String) = File(dir(context), "${googleId.hashCode().toUInt()}.jpg")

    fun items(context: Context): List<MediaItem> = cached ?: read(context).also { cached = it }

    @VisibleForTesting
    fun forget() {
        cached = null
    }

    /** Sent photos that are no longer in the phone. */
    fun missing(context: Context, local: List<MediaItem>): List<MediaItem> {
        val inPhone = local.map { it.name }.toHashSet()
        return items(context).filter { it.name !in inPhone }
    }

    /**
     * The phone's photos plus previews of both kinds, each photo once; the order of [local] stays.
     * Used by the screens and by the Kalendář app, so both show the same.
     */
    fun withCloud(
        context: Context,
        local: List<MediaItem>,
        library: CloudLibrary = CloudLibrary(context),
        show: Boolean = Prefs.get(context).showCloud.value,
    ): List<MediaItem> {
        if (!show) return local
        val extra = (library.asMediaItems(local) + missing(context, local)).distinctBy { it.name to it.date / 60_000 }
        return if (extra.isEmpty()) local else (local + extra).sortedByDescending { it.date }
    }

    /** What the Kalendář app sees: its own switch decides about the previews. */
    fun forCalendar(context: Context) =
        withCloud(context, MediaRepository(context).loadAll(), show = Prefs.get(context).calendarCloud.value)

    /** The preview of either kind, for the Kalendář app. */
    fun previewFile(context: Context, id: Long): File? =
        (items(context) + CloudLibrary(context).asMediaItems(emptyList())).find { it.id == id }?.uri?.path?.let(::File)

    /** Reads the list from every connected account and downloads previews that are missing. */
    suspend fun refresh(context: Context) = withContext(Dispatchers.IO) {
        val prefs = Prefs.get(context)
        val found = mutableListOf<JSONObject>()
        // An account whose login lapsed keeps its previews until it can be read again
        var complete = true
        for (account in prefs.accounts.value) {
            // Granted when freeing space; without it there is nothing to show yet
            val token = GoogleAuth.silentToken(context, account, GoogleAuth.READ)
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
            cloud = true,
            link = json.optString("productUrl").ifEmpty { null },
        )
    }
}

/** Downloads the previews only on Wi-Fi and above the battery threshold (or while charging). */
class CloudWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs.get(applicationContext)
        if (!prefs.cloudWanted || prefs.accounts.value.isEmpty()) return Result.success()
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
        /** Twice a day, plus once when the app is opened; the worker itself checks the battery threshold. */
        fun schedule(context: Context) = scheduleOnWifi<CloudWorker>(context, "cloud", 12, Prefs.get(context).cloudWanted)
    }
}

val wifiOnly = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED)
    .setRequiresBatteryNotLow(true)
    .build()

/** Runs the worker on Wi-Fi every [hours] and once right now; stops it when [enabled] is off. */
inline fun <reified W : ListenableWorker> scheduleOnWifi(context: Context, name: String, hours: Long, enabled: Boolean) {
    val work = WorkManager.getInstance(context)
    if (!enabled) {
        work.cancelUniqueWork("$name-periodic")
        work.cancelUniqueWork("$name-once")
        return
    }
    val periodic = PeriodicWorkRequestBuilder<W>(hours, TimeUnit.HOURS).setConstraints(wifiOnly).build()
    work.enqueueUniquePeriodicWork("$name-periodic", ExistingPeriodicWorkPolicy.KEEP, periodic)
    work.enqueueUniqueWork("$name-once", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<W>().setConstraints(wifiOnly).build())
}

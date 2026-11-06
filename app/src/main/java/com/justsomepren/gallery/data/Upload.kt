package com.justsomepren.gallery.data

import android.accounts.Account
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

object GoogleAuth {
    private const val SCOPE = "https://www.googleapis.com/auth/photoslibrary.appendonly"

    fun request(email: String): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .setAccount(Account(email, "com.google"))
        .build()

    /** A token without any dialog, or null when the user has to confirm the login again. */
    suspend fun silentToken(context: Context, email: String): String? = runCatching {
        Identity.getAuthorizationClient(context).authorize(request(email)).await()
    }.getOrNull()?.takeIf { !it.hasResolution() }?.accessToken
}

class HttpException(val code: Int, message: String) : IOException("HTTP $code: $message")

/** The few calls of the Google Photos Library API the app needs. */
class PhotosApi(private val context: Context, private val token: String) {

    fun upload(item: QueueItem): String {
        val connection = open("https://photoslibrary.googleapis.com/v1/uploads")
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setRequestProperty("X-Goog-Upload-Content-Type", item.mime)
        connection.setRequestProperty("X-Goog-Upload-Protocol", "raw")
        val uri = Uri.parse(item.uri)
        val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        if (length > 0) connection.setFixedLengthStreamingMode(length) else connection.setChunkedStreamingMode(256 * 1024)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            connection.outputStream.use { input.copyTo(it) }
        }
        return read(connection)
    }

    /** Returns the upload tokens Google Photos accepted. */
    fun create(uploads: List<Pair<String, QueueItem>>, albumId: String?): Set<String> {
        val body = JSONObject().put("newMediaItems", JSONArray(uploads.map { (token, item) ->
            JSONObject().put("simpleMediaItem", JSONObject().put("uploadToken", token).put("fileName", item.name))
        }))
        if (albumId != null) body.put("albumId", albumId)
        val results = JSONObject(post("https://photoslibrary.googleapis.com/v1/mediaItems:batchCreate", body))
            .optJSONArray("newMediaItemResults") ?: JSONArray()
        return (0 until results.length()).map { results.getJSONObject(it) }
            .filter { it.has("mediaItem") }
            .map { it.getString("uploadToken") }
            .toSet()
    }

    fun createAlbum(title: String): String {
        val body = JSONObject().put("album", JSONObject().put("title", title))
        return JSONObject(post("https://photoslibrary.googleapis.com/v1/albums", body)).getString("id")
    }

    private fun post(url: String, body: JSONObject): String {
        val connection = open(url)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        return read(connection)
    }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 30_000
        readTimeout = 120_000
        setRequestProperty("Authorization", "Bearer $token")
    }

    private fun read(connection: HttpURLConnection): String {
        val code = connection.responseCode
        if (code !in 200..299) {
            val error = connection.errorStream?.bufferedReader()?.readText().orEmpty()
            throw HttpException(code, error.take(300))
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}

/**
 * Sends the queue in the background. Runs only on Wi-Fi (unless mobile data is allowed),
 * and below the battery threshold only while charging.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val prefs = Prefs.get(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val now = inputData.getBoolean(NOW, false)
        if (!now && lowBattery()) return@withContext Result.retry()

        prefs.collectAuto(MediaRepository(applicationContext).loadAll())
        val total = prefs.queue.value.size
        if (total == 0) return@withContext Result.success()

        runCatching { setForeground(foregroundInfo(0, total)) }
        var done = 0
        try {
            for ((account, items) in prefs.queue.value.groupBy { it.account }) {
                val token = GoogleAuth.silentToken(applicationContext, account)
                if (token == null) {
                    prefs.accountProblems.value += account
                    continue
                }
                val api = PhotosApi(applicationContext, token)
                for (batch in items.chunked(BATCH)) {
                    if (isStopped) return@withContext Result.retry()
                    send(api, account, batch)
                    done += batch.size
                    prefs.uploading.value = done to total
                    runCatching { setForeground(foregroundInfo(done, total)) }
                }
            }
        } catch (e: IOException) {
            return@withContext Result.retry()
        } finally {
            prefs.uploading.value = null
        }
        if (prefs.queue.value.isEmpty()) Result.success() else Result.retry()
    }

    private fun send(api: PhotosApi, account: String, batch: List<QueueItem>) {
        val uploads = mutableListOf<Pair<String, QueueItem>>()
        for (item in batch) {
            try {
                uploads += api.upload(item) to item
            } catch (e: FileNotFoundException) {
                // Deleted from the phone in the meantime.
                prefs.removeFromQueue(listOf(item))
            }
        }
        for ((album, group) in uploads.groupBy { it.second.album }) {
            val accepted = createInAlbum(api, account, album, group)
            val (ok, failed) = group.partition { it.first in accepted }
            prefs.markSent(ok.map { it.second })
            prefs.markFailed(failed.map { it.second })
        }
    }

    private fun createInAlbum(api: PhotosApi, account: String, album: String?, group: List<Pair<String, QueueItem>>): Set<String> {
        if (album == null) return api.create(group, null)
        val albumId = prefs.albumId(account, album) ?: api.createAlbum(album).also { prefs.setAlbumId(account, album, it) }
        return try {
            api.create(group, albumId)
        } catch (e: HttpException) {
            // The album was deleted in Google Photos; make a new one next time.
            if (e.code == 400) prefs.setAlbumId(account, album, null)
            throw e
        }
    }

    private fun lowBattery(): Boolean {
        val battery = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return false
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) * 100 /
            battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return !charging && level < prefs.threshold.value
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Odesílání fotek", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Odesílám fotky")
            .setContentText("$done z $total")
            .setProgress(total, done, false)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val BATCH = 20
        private const val NOW = "now"
        private const val CHANNEL = "upload"
        private const val NOTIFICATION = 1
        private const val PERIODIC = "upload-periodic"
        private const val ONCE = "upload-once"

        /**
         * Keeps the background sending in line with the settings: a periodic check while some
         * event sends automatically, and one run as soon as there is something in the queue.
         */
        fun schedule(context: Context) {
            val prefs = Prefs.get(context)
            val work = WorkManager.getInstance(context)
            val constraints = constraints(prefs.mobileData.value)

            if (prefs.events.value.any { it.autoSend && it.endDay >= today() }) {
                val periodic = PeriodicWorkRequestBuilder<UploadWorker>(1, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()
                work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
            } else {
                work.cancelUniqueWork(PERIODIC)
            }

            if (prefs.queue.value.isNotEmpty()) {
                val once = OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints).build()
                work.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, once)
            }
        }

        /** "Send now": any network, battery is ignored. */
        fun sendNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints(mobileData = true))
                .setInputData(workDataOf(NOW to true))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
        }

        private fun constraints(mobileData: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
    }
}

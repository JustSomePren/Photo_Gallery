package com.justsomepren.gallery.data

import android.Manifest
import android.accounts.Account
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val TRANSIENT = setOf(
        CommonStatusCodes.NETWORK_ERROR,
        CommonStatusCodes.INTERNAL_ERROR,
        CommonStatusCodes.TIMEOUT,
        CommonStatusCodes.INTERRUPTED,
    )

    /**
     * A token without any dialog, or null when the user has to confirm the login again.
     * A passing network problem throws IOException, so the upload is simply retried later.
     */
    suspend fun silentToken(context: Context, email: String): String? {
        val result = try {
            Identity.getAuthorizationClient(context).authorize(request(email)).await()
        } catch (e: ApiException) {
            if (e.statusCode in TRANSIENT) throw IOException(e)
            return null
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e)
        }
        return result.takeIf { !it.hasResolution() }?.accessToken
    }
}

class HttpException(val code: Int, message: String) : IOException("HTTP $code: $message") {
    /** Google said no for good; unlike login, timeouts or rate limits, retrying will not help. */
    val isRefusal get() = code in 400..499 && code !in setOf(401, 408, 429)
}

/** The few calls of the Google Photos Library API the app needs. */
class PhotosApi(private val context: Context, private val token: String) {

    fun upload(item: QueueItem): String {
        val connection = open("https://photoslibrary.googleapis.com/v1/uploads")
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setRequestProperty("X-Goog-Upload-Content-Type", item.mime)
        connection.setRequestProperty("X-Goog-Upload-Protocol", "raw")
        val uri = originalUri(context, Uri.parse(item.uri))
        val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        if (length > 0) connection.setFixedLengthStreamingMode(length) else connection.setChunkedStreamingMode(256 * 1024)
        context.contentResolver.openInputStream(uri)!!.use { input ->
            connection.outputStream.use { input.copyTo(it) }
        }
        return read(connection)
    }

    /** Which upload tokens Google Photos accepted, and which hit a passing error worth another try. */
    data class Created(val accepted: Set<String>, val tryLater: Set<String>)

    fun create(uploads: List<Pair<String, QueueItem>>, albumId: String?): Created {
        val body = JSONObject().put("newMediaItems", JSONArray(uploads.map { (token, item) ->
            JSONObject().put("simpleMediaItem", JSONObject().put("uploadToken", token).put("fileName", item.name))
        }))
        if (albumId != null) body.put("albumId", albumId)
        val results = JSONObject(post("https://photoslibrary.googleapis.com/v1/mediaItems:batchCreate", body))
            .optJSONArray("newMediaItemResults") ?: JSONArray()
        val all = (0 until results.length()).map { results.getJSONObject(it) }
        return Created(
            accepted = all.filter { it.has("mediaItem") }.map { it.getString("uploadToken") }.toSet(),
            tryLater = all.filter { it.optJSONObject("status")?.optInt("code") in PASSING_ERRORS }
                .map { it.getString("uploadToken") }.toSet(),
        )
    }

    companion object {
        // gRPC codes Google asks to retry: deadline exceeded, resource exhausted, internal, unavailable.
        private val PASSING_ERRORS = setOf(4, 8, 13, 14)
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

    // The periodic and the one-time run share the queue; only one may send at a time.
    override suspend fun doWork(): Result = sending.withLock { withContext(Dispatchers.IO) { sendQueue() } }

    private suspend fun sendQueue(): Result {
        if (!inputData.getBoolean(NOW, false) && lowBattery()) {
            // Waiting for the charger instead of retrying, so it starts right after plugging in.
            waitForCharger(applicationContext)
            return Result.success()
        }

        val media = MediaRepository(applicationContext).loadAll()
        // Last check of the owner's rule: only photos and videos taken with the camera ever leave the phone.
        val camera = media.filter { it.isCameraShot }.associateBy { it.key }
        val unseen = prefs.queue.value.filter { it.key !in camera }
        // Remove them only when the app sees all photos; with limited access they just wait.
        if (seesAllPhotos() && media.isNotEmpty()) prefs.removeFromQueue(unseen)
        // The stored link may point to another file after a reindex; send the camera photo with that key.
        prefs.followFiles(camera)
        prefs.collectAuto(media)
        val sendable = prefs.queue.value.filter { it.key in camera }
        val total = sendable.size
        if (total == 0) return Result.success()

        prefs.uploading.value = 0 to total
        runCatching { setForeground(foregroundInfo(0, total)) }
        var done = 0
        try {
            for ((account, items) in sendable.groupBy { it.account }) {
                val token = GoogleAuth.silentToken(applicationContext, account)
                prefs.setAccountProblem(account, token == null)
                if (token == null) continue
                val api = PhotosApi(applicationContext, token)
                for (chunk in items.chunked(BATCH)) {
                    if (isStopped) return Result.retry()
                    val batch = chunk.mapNotNull(::current)
                    if (batch.isNotEmpty()) send(api, account, batch)
                    done += chunk.size
                    prefs.uploading.value = done to total
                    runCatching { setForeground(foregroundInfo(done, total)) }
                }
            }
        } catch (e: IOException) {
            return Result.retry()
        } finally {
            prefs.uploading.value = null
        }
        // What only waits for a new login is sent after it (that restarts the upload); no reason to retry.
        val waiting = prefs.queue.value.filter { it.key in camera && it.account !in prefs.accountProblems.value }
        return if (waiting.isEmpty()) Result.success() else Result.retry()
    }

    /**
     * The item as it is in the queue right now: the user may have cleared the queue, removed the
     * account or changed the event's album while this run was going.
     */
    private fun current(item: QueueItem): QueueItem? =
        prefs.queue.value.firstOrNull { it.sentKey == item.sentKey }?.takeIf { it.account in prefs.accounts.value }

    private fun send(api: PhotosApi, account: String, batch: List<QueueItem>) {
        val uploads = mutableListOf<Pair<String, QueueItem>>()
        for (queued in batch) {
            val item = current(queued) ?: continue
            try {
                uploads += api.upload(item) to item
            } catch (e: FileNotFoundException) {
                // Deleted from the phone in the meantime.
                prefs.removeFromQueue(listOf(item))
            } catch (e: SecurityException) {
                // The app may no longer read this photo; don't let it block the rest.
                prefs.markFailed(listOf(item))
            } catch (e: HttpException) {
                // Google refused this one file; network and login problems still retry the whole run.
                if (e.isRefusal) prefs.markFailed(listOf(item)) else throw e
            }
        }
        // Uploading can take minutes; add only what is still wanted, with its current album.
        val wanted = uploads.mapNotNull { (token, item) -> current(item)?.let { token to it } }
        for ((album, group) in wanted.groupBy { it.second.album }) {
            val created = createInAlbum(api, account, album, group)
            prefs.markSent(group.filter { it.first in created.accepted }.map { it.second })
            // Passing errors stay in the queue for the next run; only real refusals count as failed.
            prefs.markFailed(group.filter { it.first !in created.accepted && it.first !in created.tryLater }.map { it.second })
        }
    }

    /** A lasting refusal accepts none, so the group is marked failed. */
    private fun createInAlbum(
        api: PhotosApi,
        account: String,
        album: String?,
        group: List<Pair<String, QueueItem>>,
    ): PhotosApi.Created {
        val stored = album?.let { prefs.albumId(account, it) }
        return try {
            val albumId = stored ?: album?.let { api.createAlbum(it).also { id -> prefs.setAlbumId(account, album, id) } }
            api.create(group, albumId)
        } catch (e: HttpException) {
            if (!e.isRefusal) throw e
            if (stored != null) {
                // The album was probably deleted in Google Photos; try again with a new one.
                prefs.setAlbumId(account, album, null)
                throw e
            }
            PhotosApi.Created(emptySet(), emptySet())
        }
    }

    private fun seesAllPhotos() =
        applicationContext.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED

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
        private const val CHARGING = "upload-charging"
        private val sending = Mutex()

        /**
         * Keeps the background sending in line with the settings: a periodic check while some
         * event sends automatically, and one run as soon as there is something in the queue.
         */
        /** [restart]: replace waiting runs, so a new network setting or a new login applies right away. */
        fun schedule(context: Context, restart: Boolean = false) {
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
                val policy = if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
                work.enqueueUniqueWork(ONCE, policy, once)
            }
            if (restart) work.cancelUniqueWork(CHARGING)
        }

        private fun waitForCharger(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints(Prefs.get(context).mobileData.value, charging = true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CHARGING, ExistingWorkPolicy.KEEP, request)
        }

        /** "Send now": any network, battery is ignored. */
        fun sendNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints(mobileData = true, batteryNotLow = false))
                .setInputData(workDataOf(NOW to true))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
        }

        private fun constraints(mobileData: Boolean, batteryNotLow: Boolean = true, charging: Boolean = false) =
            Constraints.Builder()
                .setRequiredNetworkType(if (mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(batteryNotLow)
                .setRequiresCharging(charging)
                .build()
    }
}

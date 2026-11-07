package com.justsomepren.gallery.data

import android.Manifest
import android.accounts.Account
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.BatteryManager
import androidx.annotation.VisibleForTesting
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
    /** Upload into the account; the only access the queue needs. */
    const val UPLOAD = "https://www.googleapis.com/auth/photoslibrary.appendonly"

    /** Read only what this app uploaded, to check a photo is in the cloud before freeing space. */
    const val READ = "https://www.googleapis.com/auth/photoslibrary.readonly.appcreateddata"

    fun request(email: String, scope: String = UPLOAD): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(scope)))
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
    suspend fun silentToken(context: Context, email: String, scope: String = UPLOAD): String? {
        val result = try {
            Identity.getAuthorizationClient(context).authorize(request(email, scope)).await()
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

/** The photo is no longer wanted (queue cleared, account removed); the rest of the queue goes on. */
class UploadCancelled : Exception()

/** The battery dropped below the threshold during a normal run; it continues on the charger. */
class LowBattery : Exception()

/** Mobile data was switched off during a normal run on a paid network; it continues on Wi-Fi. */
class NoWifi : Exception()

class HttpException(val code: Int, message: String) : IOException("HTTP $code: $message") {
    /** Google said no for good; unlike login, timeouts or rate limits, retrying will not help. */
    val isRefusal get() = code in 400..499 && code !in setOf(401, 408, 429)
}

/** The few calls of the Google Photos Library API the app needs. */
class PhotosApi(private val context: Context, private val token: String) {

    /**
     * Uploads in pieces and remembers where it got to, so a long video continues in the next run
     * instead of starting over. Returns the upload token.
     */
    fun upload(item: QueueItem, stillWanted: () -> Boolean = { true }): String {
        val uri = originalUri(context, Uri.parse(item.uri))
        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        val prefs = Prefs.get(context)

        // A session started for a file of another size belongs to an older version of the file.
        var url = prefs.uploadSession(item.sentKey, size)
        var offset = url?.let(::receivedSoFar)
        if (url == null || offset == null) {
            url = startSession(item, size)
            offset = 0L
            prefs.setUploadSession(item.sentKey, url, size)
        }

        context.contentResolver.openInputStream(uri)!!.use { input ->
            var toSkip = offset
            while (toSkip > 0) {
                val skipped = input.skip(toSkip)
                if (skipped <= 0) throw IOException("File is shorter than the part already sent")
                toSkip -= skipped
            }
            val buffer = ByteArray(CHUNK)
            while (true) {
                // The user may remove the account or clear the queue while a long video goes up.
                if (!stillWanted()) throw UploadCancelled()
                val read = input.readNBytes(buffer, 0, CHUNK)
                val last = read < CHUNK || (size > 0 && offset + read >= size)
                val connection = open(url)
                connection.setRequestProperty("X-Goog-Upload-Command", if (last) "upload, finalize" else "upload")
                connection.setRequestProperty("X-Goog-Upload-Offset", offset.toString())
                connection.setFixedLengthStreamingMode(read)
                connection.outputStream.use { it.write(buffer, 0, read) }
                val body = read(connection)
                offset += read
                if (last) {
                    prefs.setUploadSession(item.sentKey, null, size)
                    return body
                }
            }
        }
    }

    private fun startSession(item: QueueItem, size: Long): String {
        val connection = open("$baseUrl/uploads")
        connection.setRequestProperty("X-Goog-Upload-Command", "start")
        connection.setRequestProperty("X-Goog-Upload-Content-Type", item.mime)
        connection.setRequestProperty("X-Goog-Upload-Protocol", "resumable")
        if (size > 0) connection.setRequestProperty("X-Goog-Upload-Raw-Size", size.toString())
        connection.setFixedLengthStreamingMode(0)
        connection.outputStream.close()
        read(connection)
        return connection.getHeaderField("X-Goog-Upload-URL") ?: throw IOException("No upload URL")
    }

    /** How much of an earlier upload Google already has, or null when it has to start over. */
    private fun receivedSoFar(url: String): Long? = try {
        val connection = open(url)
        connection.setRequestProperty("X-Goog-Upload-Command", "query")
        connection.setFixedLengthStreamingMode(0)
        connection.outputStream.close()
        read(connection)
        connection.takeIf { it.getHeaderField("X-Goog-Upload-Status") == "active" }
            ?.getHeaderField("X-Goog-Upload-Size-Received")?.toLongOrNull()
    } catch (e: HttpException) {
        if (e.isRefusal) null else throw e
    }

    /** Which upload tokens Google Photos accepted, and which hit a passing error worth another try. */
    /** Accepted upload tokens with the id Google Photos gave each item; passing errors worth another try. */
    data class Created(val accepted: Map<String, String>, val tryLater: Set<String>)

    fun create(uploads: List<Pair<String, QueueItem>>, albumId: String?): Created {
        val body = JSONObject().put("newMediaItems", JSONArray(uploads.map { (token, item) ->
            JSONObject().put("simpleMediaItem", JSONObject().put("uploadToken", token).put("fileName", item.name))
        }))
        if (albumId != null) body.put("albumId", albumId)
        val results = JSONObject(post("$baseUrl/mediaItems:batchCreate", body))
            .optJSONArray("newMediaItemResults") ?: JSONArray()
        val all = (0 until results.length()).map { results.getJSONObject(it) }
        return Created(
            accepted = all.filter { it.has("mediaItem") }
                .associate { it.getString("uploadToken") to it.getJSONObject("mediaItem").optString("id") },
            tryLater = all.filter { it.optJSONObject("status")?.optInt("code") in PASSING_ERRORS }
                .map { it.getString("uploadToken") }.toSet(),
        )
    }

    companion object {
        // gRPC codes Google asks to retry: deadline exceeded, resource exhausted, internal, unavailable.
        private val PASSING_ERRORS = setOf(4, 8, 13, 14)

        // 8 MB, a multiple of Google's 256 KB chunk size.
        @VisibleForTesting
        var CHUNK = 8 * 1024 * 1024

        @VisibleForTesting
        var baseUrl = "https://photoslibrary.googleapis.com/v1"
    }

    /** Which of these items (created by this app) are in the account now, with their file names. */
    fun existing(ids: List<String>): Map<String, String> = ids.chunked(50).flatMap { chunk ->
        val query = chunk.joinToString("&") { "mediaItemIds=" + java.net.URLEncoder.encode(it, "UTF-8") }
        val connection = open("$baseUrl/mediaItems:batchGet?$query").apply {
            requestMethod = "GET"
            doOutput = false
        }
        val results = JSONObject(read(connection)).optJSONArray("mediaItemResults") ?: JSONArray()
        (0 until results.length()).mapNotNull { results.getJSONObject(it).optJSONObject("mediaItem") }
            .map { it.getString("id") to it.optString("filename") }
    }.toMap()

    fun createAlbum(title: String): String {
        val body = JSONObject().put("album", JSONObject().put("title", title))
        return JSONObject(post("$baseUrl/albums", body)).getString("id")
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
    override suspend fun doWork(): Result = sending.withLock {
        withContext(Dispatchers.IO) { sendQueue() }.also { result ->
            // A finished "send now" run ends it, also when there turned out to be nothing to send.
            if (result == Result.success() && (inputData.getBoolean(NOW, false) || prefs.queue.value.isEmpty())) {
                prefs.setSendNowPending(false)
            }
        }
    }

    /** "Odeslat hned" ignores the battery. */
    private val now get() = inputData.getBoolean(NOW, false)
    private val batteryStop get() = !now && lowBattery()
    private val wifiStop get() = !now && !prefs.mobileData.value &&
        applicationContext.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    /** Stops a normal run that may no longer go on: below the battery threshold or on paid data. */
    private fun checkPause() {
        if (batteryStop) throw LowBattery()
        if (wifiStop) throw NoWifi()
    }

    /** Sizes of the camera files by key, recorded with each upload. */
    private var fileSizes: Map<String, Long> = emptyMap()

    /** A photo hit a passing error this run and waits in the queue for the next one. */
    private var needsRetry = false

    private suspend fun sendQueue(): Result {
        if (batteryStop) return pause(charger = true)

        val media = MediaRepository(applicationContext).loadAll()
        // Last check of the owner's rule: only photos and videos taken with the camera ever leave the phone.
        val camera = media.filter { it.isCameraShot }.associateBy { it.key }
        fileSizes = camera.mapValues { it.value.size }
        val unseen = prefs.queue.value.filter { it.key !in camera }
        // Remove them only when the app sees all photos; with limited access they just wait.
        if (seesAllPhotos() && media.isNotEmpty()) prefs.removeFromQueue(unseen)
        // The stored link may point to another file after a reindex; send the camera photo with that key.
        prefs.followFiles(camera)
        prefs.collectAuto(media)
        val sendable = prefs.queue.value.filter { it.key in camera }
        val atStart = prefs.queue.value.map { it.sentKey }.toSet()
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
                    if (batch.isNotEmpty()) {
                        try {
                            send(api, account, batch)
                        } catch (e: LowBattery) {
                            return pause(charger = true)
                        } catch (e: NoWifi) {
                            return pause(charger = false)
                        }
                    }
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
        // Photos queued while this run went on could not start a run of their own; take them in the next one.
        val addedMeanwhile = prefs.queue.value.any { it.sentKey !in atStart && it.account !in prefs.accountProblems.value }
        return if (waiting.isEmpty() && !needsRetry && !addedMeanwhile) Result.success() else Result.retry()
    }

    /**
     * Waits for the charger or Wi-Fi. A run that already waits for it simply retries under its own condition;
     * asking for a new one would be dropped while this one is still running.
     */
    private fun pause(charger: Boolean): Result {
        val tag = if (charger) CHARGING else WIFI
        if (tag in tags) return Result.retry()
        if (charger) waitForCharger(applicationContext) else waitForWifi(applicationContext)
        return Result.success()
    }

    /**
     * The item as it is in the queue right now: the user may have cleared the queue, removed the
     * account or changed the event's album while this run was going.
     */
    private fun current(item: QueueItem): QueueItem? =
        prefs.queue.value.firstOrNull { it.sentKey == item.sentKey }?.takeIf { it.account in prefs.accounts.value }

    private fun send(api: PhotosApi, account: String, batch: List<QueueItem>) {
        val uploads = mutableListOf<Pair<String, QueueItem>>()
        try {
            uploadEach(api, account, batch, uploads)
        } catch (e: Exception) {
            // Whatever ended the run, photos already uploaded are still added, so they are not sent again.
            runCatching { create(api, account, uploads) }
            throw e
        }
        create(api, account, uploads)
    }

    private fun uploadEach(api: PhotosApi, account: String, batch: List<QueueItem>, uploads: MutableList<Pair<String, QueueItem>>) {
        var failuresInRow = 0
        for (queued in batch) {
            checkPause()
            val item = current(queued) ?: continue
            val token = try {
                api.upload(item) { !isStopped && current(item) != null && !batteryStop && !wifiStop }
            } catch (e: UploadCancelled) {
                checkPause()
                null
            } catch (e: FileNotFoundException) {
                // Deleted from the phone in the meantime.
                prefs.removeFromQueue(listOf(item))
                null
            } catch (e: SecurityException) {
                // The app may no longer read this photo; don't let it block the rest.
                prefs.markFailed(listOf(item))
                null
            } catch (e: HttpException) {
                when {
                    // Google refused this one file.
                    e.isRefusal -> prefs.markFailed(listOf(item))
                    // The login expired: the whole run waits for a new token.
                    e.code == 401 -> throw e
                    else -> skipForNow(++failuresInRow, e)
                }
                null
            } catch (e: IOException) {
                skipForNow(++failuresInRow, e)
                null
            } ?: continue
            failuresInRow = 0
            uploads += token to item
            // A video took long to upload; add it right away rather than risk the run ending first.
            // Outside the catch above: a refused album is not a refused video.
            if (item.mime.startsWith("video/")) {
                create(api, account, uploads)
                uploads.clear()
            }
        }
    }

    /** One file had a passing error: try the others now and it next time. Several in a row mean the network is down. */
    private fun skipForNow(failuresInRow: Int, error: IOException) {
        needsRetry = true
        if (failuresInRow >= 3) throw error
    }

    private fun create(api: PhotosApi, account: String, uploads: List<Pair<String, QueueItem>>) {
        // Uploading can take minutes; add only what is still wanted, with its current album.
        val wanted = uploads.mapNotNull { (token, item) -> current(item)?.let { token to it } }
        for ((album, group) in wanted.groupBy { it.second.album }) {
            val created = createInAlbum(api, account, album, group)
            val sent = group.filter { it.first in created.accepted }
            // The id and size let "Uvolnit místo" check later that exactly this file is in the cloud.
            prefs.markSent(
                sent.map { it.second },
                ids = sent.associate { (token, item) -> item.sentKey to created.accepted.getValue(token) },
                sizes = fileSizes,
            )
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
            PhotosApi.Created(emptyMap(), emptySet())
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
        private const val WIFI = "upload-wifi"
        private const val NEXT = "upload-next"
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
                // A waiting "send now" keeps its any-network, any-battery conditions.
                val once = if (prefs.sendNowPending) sendNowRequest() else
                    OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints).build()
                val policy = if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
                work.enqueueUniqueWork(ONCE, policy, once)
            }
            if (restart) work.cancelUniqueWork(CHARGING)
            // A request under the running run's own name would be dropped; queue a follow-up after it instead.
            if (prefs.queue.value.isNotEmpty() && prefs.uploading.value != null) {
                val next = OneTimeWorkRequestBuilder<UploadWorker>().setConstraints(constraints).build()
                work.enqueueUniqueWork(NEXT, ExistingWorkPolicy.KEEP, next)
            }
        }

        private fun waitForWifi(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints(mobileData = false))
                .addTag(WIFI)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WIFI, ExistingWorkPolicy.KEEP, request)
        }

        private fun waitForCharger(context: Context) {
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints(Prefs.get(context).mobileData.value, charging = true))
                .addTag(CHARGING)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(CHARGING, ExistingWorkPolicy.KEEP, request)
        }

        /** "Send now": any network, battery is ignored. */
        fun sendNow(context: Context) {
            Prefs.get(context).setSendNowPending(true)
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, sendNowRequest())
        }

        private fun sendNowRequest() = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints(mobileData = true, batteryNotLow = false))
            .setInputData(workDataOf(NOW to true))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        private fun constraints(mobileData: Boolean, batteryNotLow: Boolean = true, charging: Boolean = false) =
            Constraints.Builder()
                .setRequiredNetworkType(if (mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(batteryNotLow)
                .setRequiresCharging(charging)
                .build()
    }
}

package com.justsomepren.gallery.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Previews from a Google Takeout export of Google Photos: the ZIP files are read in the phone,
 * only small previews are kept, nothing is sent anywhere. Google puts the date of each photo
 * into a JSON file next to it, often in another ZIP part, so dates are matched after all files are read.
 */
object Takeout {
    data class Progress(val read: Int = 0, val added: Int = 0, val done: Boolean = false, val error: String? = null)

    val progress = MutableStateFlow<Progress?>(null)

    private const val WORK = "takeout"
    private const val PREVIEW = 400
    private const val MAX_PHOTO = 80L shl 20

    private val types = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "heic" to "image/heic",
        "heif" to "image/heif", "webp" to "image/webp", "gif" to "image/gif", "dng" to "image/x-adobe-dng",
        "mp4" to "video/mp4", "mov" to "video/quicktime", "3gp" to "video/3gpp", "webm" to "video/webm", "mkv" to "video/x-matroska",
    )

    fun start(context: Context, files: List<Uri>) {
        files.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        progress.value = Progress()
        val request = OneTimeWorkRequestBuilder<TakeoutWorker>()
            .setInputData(workDataOf("files" to files.map { it.toString() }.toTypedArray()))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun stop(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        progress.value = null
    }

    private class Found(val folder: String, val name: String, val mime: String, val preview: File, val taken: Long?)

    /**
     * Reads the ZIP files one after another and adds a preview of each photo and video to [library].
     * Skips photos that are still in the phone ([onPhone]: name and minute) and those imported before.
     */
    fun import(
        context: Context,
        library: CloudLibrary,
        files: List<() -> InputStream>,
        onPhone: Set<Pair<String, Long>>,
        stopped: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
    ): Progress {
        val temp = File(context.cacheDir, "takeout").apply { deleteRecursively(); mkdirs() }
        val found = mutableListOf<Found>()
        val dates = HashMap<String, Long>()
        // The same photo is in the year folder and in each of its albums
        val seen = HashSet<Pair<String, Int>>()
        var progress = Progress()
        try {
            for (open in files) {
                ZipInputStream(open().buffered()).use { zip ->
                    while (true) {
                        if (stopped()) return progress
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val folder = entry.name.substringBeforeLast('/', "")
                        val name = entry.name.substringAfterLast('/')
                        val extension = name.substringAfterLast('.', "").lowercase()
                        if (extension == "json") {
                            readDate(zip)?.let { (title, taken) ->
                                dates["$folder/$title"] = taken
                                dates.putIfAbsent(title, taken)
                            }
                            continue
                        }
                        val mime = types[extension] ?: continue
                        val preview = File(temp, "${found.size}.jpg")
                        val taken = if (mime.startsWith("video")) videoPreview(zip, temp, preview)
                        else photoPreview(zip, preview) { size -> seen.add(name to size) }
                        if (!preview.exists()) continue
                        found += Found(folder, name, mime, preview, taken)
                        progress = progress.copy(read = found.size)
                        onProgress(progress)
                    }
                }
            }
            val added = HashSet<String>()
            for (item in found) {
                val taken = dates["${item.folder}/${original(item.name)}"] ?: dates[original(item.name)] ?: item.taken ?: continue
                val id = "takeout:${item.name}:${taken / 60_000}"
                if (library.has(id) || id in added || (item.name to taken / 60_000) in onPhone) continue
                library.add(CloudPhoto(id, taken, item.name, item.mime), item.preview.readBytes())
                added += id
            }
            return progress.copy(added = added.size, done = true)
        } finally {
            temp.deleteRecursively()
        }
    }

    /** "IMG_1(1).jpg" and "IMG_1-edited.jpg" have their date under "IMG_1.jpg". */
    private fun original(name: String) = name.replace(Regex("""(-edited|\(\d+\))(?=\.[^.]+$)"""), "")

    private fun readDate(zip: InputStream): Pair<String, Long>? = runCatching {
        val json = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
        val taken = json.getJSONObject("photoTakenTime").getString("timestamp").toLong() * 1000
        json.getString("title") to taken
    }.getOrNull()?.takeIf { it.second > 0 }

    /** Null date when the photo has none; [isNew] says whether the same name and size came already. */
    private fun photoPreview(zip: InputStream, preview: File, isNew: (Int) -> Boolean): Long? {
        val bytes = readUpTo(zip, MAX_PHOTO) ?: return null
        if (!isNew(bytes.size)) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val exif = runCatching { ExifInterface(ByteArrayInputStream(bytes)) }.getOrNull()
        save(rotate(bitmap, exif?.rotationDegrees ?: 0), preview)
        return exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let(::exifDate)
    }

    /** A video goes through a file of its own: the frame cannot be read from inside the ZIP. */
    private fun videoPreview(zip: InputStream, temp: File, preview: File): Long? {
        val video = File(temp, "video")
        try {
            video.outputStream().use { zip.copyTo(it) }
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(video.path)
                val frame = retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, PREVIEW, PREVIEW)
                frame?.let { save(it, preview) }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let(::videoDate)
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            return null
        } finally {
            video.delete()
        }
    }

    private fun readUpTo(input: InputStream, limit: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap =
        if (degrees == 0) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)

    private fun save(bitmap: Bitmap, file: File) {
        val scale = PREVIEW.toFloat() / minOf(bitmap.width, bitmap.height)
        val small = if (scale < 1) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true) else bitmap
        file.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }
    }

    private fun exifDate(text: String) = runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(text)?.time }.getOrNull()

    private fun videoDate(text: String) = runCatching {
        SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(text)?.time
    }.getOrNull()?.takeIf { it > 0 }
}

/** Runs the import with a notification, so it goes on when the app is closed. */
class TakeoutWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val files = inputData.getStringArray("files").orEmpty().map(Uri::parse)
        runCatching {
            setForeground(
                applicationContext.progressNotice(
                    "takeout", "Import z Google Takeout", 2, "Načítám fotky z Takeoutu", "Ukládám jen malé náhledy", 0, 0,
                    android.R.drawable.stat_sys_download,
                ),
            )
        }
        val onPhone = MediaRepository(applicationContext).loadAll().map { it.name to it.date / 60_000 }.toSet()
        val result = try {
            Takeout.import(
                applicationContext,
                CloudLibrary(applicationContext),
                files.map { uri -> { applicationContext.contentResolver.openInputStream(uri)!! } },
                onPhone,
                stopped = { isStopped },
                onProgress = { Takeout.progress.value = it },
            )
        } catch (e: Exception) {
            Takeout.Progress(done = true, error = e.message?.take(120) ?: "Soubor nejde přečíst")
        }
        files.forEach { runCatching { applicationContext.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        if (!isStopped) Takeout.progress.value = result
        CloudPhotos.version.value++
        Result.success()
    }
}

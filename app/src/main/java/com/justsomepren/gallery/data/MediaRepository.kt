package com.justsomepren.gallery.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.exifinterface.media.ExifInterface
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val mime: String,
    val isVideo: Boolean,
    val date: Long,
    /** 0 when the file has no capture date (e.g. some downloads). */
    val dateTaken: Long,
    val bucketId: Long,
    val bucketName: String,
    val isFavorite: Boolean,
    val name: String,
    val width: Int,
    val height: Int,
    val size: Long,
    val relativePath: String,
) {
    /** Survives a reindex or a new phone, unlike [id]. The date added changes there, so it is not used. */
    val key get() = if (dateTaken > 0) "$name|$dateTaken" else "$name|$size"

    /** Screenshots and screen recordings; some phones keep them in DCIM too. */
    val isScreenshot get() = relativePath.contains("Screenshot", ignoreCase = true) ||
        relativePath.contains("Screen record", ignoreCase = true)

    /**
     * Really taken with the camera: camera apps save to DCIM. Leaves out screenshots,
     * downloads and pictures received in chat apps. Used for the calendar and its events.
     */
    val isCamera get() = relativePath.startsWith("DCIM/", ignoreCase = true) && !isScreenshot

    /** Sharp, detailed photos compress worse, so more bytes per pixel usually means a better shot. */
    val detail get() = if (width > 0 && height > 0) size.toDouble() / (width.toLong() * height) else 0.0
}

/** The photo to show for a day or an event: a favorite first, then the most detailed photo, videos last. */
fun List<MediaItem>.cover(): MediaItem? =
    maxWithOrNull(compareBy<MediaItem>({ it.isFavorite }, { !it.isVideo }, { it.detail }))

data class MediaDetails(val rows: List<Pair<String, String>>, val location: DoubleArray?)

/**
 * Reads straight from MediaStore. Android already indexes the photos,
 * so the app keeps no own database and does no background scanning.
 */
class MediaRepository(private val context: Context) {

    fun loadAll(): List<MediaItem> =
        (query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false) +
            query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true))
            .sortedByDescending { it.date }

    private fun query(collection: Uri, isVideo: Boolean): List<MediaItem> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
            MediaStore.MediaColumns.IS_FAVORITE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val result = mutableListOf<MediaItem>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val taken = if (c.isNull(2)) 0L else c.getLong(2)
                result += MediaItem(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    mime = c.getString(1) ?: if (isVideo) "video/*" else "image/*",
                    isVideo = isVideo,
                    date = if (taken > 0) taken else c.getLong(3) * 1000,
                    dateTaken = taken,
                    bucketId = c.getLong(4),
                    bucketName = c.getString(5) ?: "Bez názvu",
                    isFavorite = c.getInt(6) == 1,
                    name = c.getString(7) ?: "",
                    width = c.getInt(8),
                    height = c.getInt(9),
                    size = c.getLong(10),
                    relativePath = c.getString(11) ?: "",
                )
            }
        }
        return result
    }

    fun details(item: MediaItem): MediaDetails {
        val rows = mutableListOf(
            "Datum" to DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(item.date)),
            "Soubor" to item.name,
            "Složka" to item.relativePath,
            "Rozlišení" to "${item.width} × ${item.height}",
            "Velikost" to Formatter.formatShortFileSize(context, item.size),
        )
        if (item.isVideo) return MediaDetails(rows, null)
        val exif = runCatching { readExif(item.uri) }.getOrNull() ?: return MediaDetails(rows, null)

        val camera = listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE), exif.getAttribute(ExifInterface.TAG_MODEL))
        if (camera.isNotEmpty()) rows += "Fotoaparát" to camera.joinToString(" ")

        val exposure = listOfNotNull(
            exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" },
            exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }
                ?.let { if (it < 1) "1/${(1 / it).roundToInt()} s" else "${it.roundToInt()} s" },
            exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" },
            exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }
                ?.let { "%.1f mm".format(it) },
        )
        if (exposure.isNotEmpty()) rows += "Expozice" to exposure.joinToString(" · ")

        val location = exif.latLong?.takeIf { it[0] != 0.0 || it[1] != 0.0 }
        if (location != null) rows += "Poloha" to "%.5f, %.5f".format(location[0], location[1])
        return MediaDetails(rows, location)
    }

    fun location(item: MediaItem): DoubleArray? =
        if (item.isVideo) null else runCatching { readExif(item.uri).latLong }.getOrNull()?.takeIf { it[0] != 0.0 || it[1] != 0.0 }

    /** Without the original file Android hides the GPS position. */
    private fun readExif(uri: Uri): ExifInterface {
        val canSeeLocation = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val source = if (canSeeLocation) MediaStore.setRequireOriginal(uri) else uri
        return context.contentResolver.openInputStream(source)!!.use { ExifInterface(it) }
    }
}

sealed interface Source {
    data object All : Source
    data object Favorites : Source
    data class Folder(val bucketId: Long, val name: String) : Source
    data class Album(val id: String, val name: String) : Source

    /** A day or a range of days, e.g. a day tapped in the calendar or an event. */
    data class Period(val name: String, val start: Long, val end: Long, val cameraOnly: Boolean = false) : Source

    /** A time range in millis, end exclusive, e.g. a birthday party from the Kalendář app. */
    data class Span(val name: String, val from: Long, val to: Long) : Source
}

val Source.title: String
    get() = when (this) {
        Source.All -> "Fotky"
        Source.Favorites -> "Oblíbené"
        is Source.Folder -> name
        is Source.Album -> name
        is Source.Period -> name
        is Source.Span -> name
    }

fun List<MediaItem>.filterBy(source: Source, albums: List<CustomAlbum>): List<MediaItem> =
    when (source) {
        Source.All -> this
        Source.Favorites -> filter { it.isFavorite }
        is Source.Folder -> filter { it.bucketId == source.bucketId }
        is Source.Album -> {
            val keys = albums.find { it.id == source.id }?.refs.orEmpty().map { it.key }.toSet()
            filter { it.key in keys }
        }
        is Source.Period -> filter { it.day in source.start..source.end && (!source.cameraOnly || it.isCamera) }
        is Source.Span -> filter { it.date >= source.from && it.date < source.to && it.isCamera }
    }

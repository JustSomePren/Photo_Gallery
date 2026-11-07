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
    /** Changes when the file is edited, so pictures are loaded again. */
    val modified: Long = 0,
    /** Only in Google Photos; shown from a small preview, never sent, moved or deleted. */
    val cloud: Boolean = false,
) {

    /** Survives a reindex or a new phone, unlike [id]. The date added changes there, so it is not used. */
    val key get() = if (dateTaken > 0) "$name|$dateTaken" else "$name|$size"

    /** Taken with the phone's camera; screenshots, downloads and chat photos are never sent to the cloud. */
    val isCameraShot get() = relativePath.startsWith("DCIM/Camera/", ignoreCase = true)

    /** Shown in the calendar and with events: camera photos and previews of photos in Google Photos. */
    val inCalendar get() = isCameraShot || cloud

    /** Sharp, detailed photos compress worse, so more bytes per pixel usually means a better shot. */
    val detail get() = if (width > 0 && height > 0) size.toDouble() / (width.toLong() * height) else 0.0
}

/** The best photos first: favorites, then photos before videos, then the most detailed. */
fun List<MediaItem>.bestFirst(): List<MediaItem> =
    sortedWith(compareByDescending<MediaItem> { it.isFavorite }.thenByDescending { !it.isVideo }.thenByDescending { it.detail })

/** The photo to show for a day or an event. */
fun List<MediaItem>.cover(): MediaItem? = bestFirst().firstOrNull()

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
            MediaStore.MediaColumns.DATE_MODIFIED,
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
                    date = bestDate(taken, c.getString(7).orEmpty(), c.getString(11).orEmpty(), c.getLong(3), c.getLong(12)),
                    dateTaken = taken,
                    bucketId = c.getLong(4),
                    bucketName = c.getString(5) ?: "Bez názvu",
                    isFavorite = c.getInt(6) == 1,
                    name = c.getString(7) ?: "",
                    width = c.getInt(8),
                    height = c.getInt(9),
                    size = c.getLong(10),
                    relativePath = c.getString(11) ?: "",
                    modified = c.getLong(12),
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

    private fun readExif(uri: Uri): ExifInterface =
        context.contentResolver.openInputStream(originalUri(context, uri))!!.use { ExifInterface(it) }
}

/** Without the original file Android hides the GPS position, in the info and in uploaded photos. */
fun originalUri(context: Context, uri: Uri): Uri {
    val canSeeLocation = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    return if (canSeeLocation) MediaStore.setRequireOriginal(uri) else uri
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
        is Source.Period -> filter { it.day in source.start..source.end && (!source.cameraOnly || it.inCalendar) }
        is Source.Span -> filter { it.date >= source.from && it.date < source.to && it.inCalendar }
    }

/**
 * The capture date for camera photos. Other files (WhatsApp, downloads) often have none, or one set when
 * they arrived on this phone; a date in their name is more reliable then.
 */
internal fun bestDate(taken: Long, name: String, relativePath: String, addedSeconds: Long, modifiedSeconds: Long): Long {
    val camera = relativePath.startsWith("DCIM/Camera/", ignoreCase = true)
    val fromName = if (camera) null else dateFromName(name)
    return when {
        taken > 0 && (fromName == null || kotlin.math.abs(fromName - taken) < 86_400_000L) -> taken
        fromName != null -> fromName
        else -> fallbackDate(name, addedSeconds, modifiedSeconds)
    }
}

/**
 * For files without a capture date (WhatsApp, downloads): the date in the file name, else the older of
 * modified and added. "Added" alone would put a whole history on the day the phone was set up.
 */
internal fun fallbackDate(name: String, addedSeconds: Long, modifiedSeconds: Long): Long =
    dateFromName(name) ?: listOf(addedSeconds, modifiedSeconds).filter { it > 0 }.minOrNull()?.times(1000) ?: 0

private val datePattern = Regex(
    """(?<!\d)((?:19|20)\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])""" +
        """(?:[-_. ]?([01]\d|2[0-3])[-_.]?([0-5]\d)[-_.]?([0-5]\d))?""",
)

/** "IMG-20230514-WA0003.jpg", "Screenshot_20230514-101010.png", "PXL_20230514_101010123.jpg" → that moment. */
internal fun dateFromName(name: String): Long? {
    val m = datePattern.find(name) ?: return null
    val (y, mo, d, h, mi, se) = m.destructured
    val time = runCatching {
        java.time.LocalDateTime.of(
            y.toInt(), mo.toInt(), d.toInt(),
            h.toIntOrNull() ?: 12, mi.toIntOrNull() ?: 0, se.toIntOrNull() ?: 0,
        ).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }.getOrNull() ?: return null
    // A number that only looks like a date must not move a photo into the future.
    return time.takeIf { it <= System.currentTimeMillis() + 86_400_000L }
}

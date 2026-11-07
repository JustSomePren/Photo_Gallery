package com.justsomepren.gallery.data

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/** An event from a personal calendar in the Kalendář app. */
data class CalendarItem(
    val id: Long,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val firstDay: Long,
    val lastDay: Long,
    val title: String,
    val color: Int,
    val vacation: Boolean = false,
    val album: String? = null,
    val account: String? = null,
    val autoSend: Boolean = false,
) {
    /** Whole days for all-day events, otherwise from a bit before the start to a bit after the end. */
    val source: Source
        get() = if (allDay) Source.Period(title, firstDay, lastDay, cameraOnly = true)
        else Source.Span(title, begin - BEFORE_MS, end + AFTER_MS)

    fun covers(day: Long) = day in firstDay..lastDay

    /** Sends only to an account the user connected here, whatever the calendar says. */
    fun toEvent(accounts: List<String>) = Event(
        id = "calendar:$id:$begin",
        name = title,
        color = color,
        startDay = firstDay,
        endDay = lastDay,
        autoSend = autoSend && account in accounts,
        account = account,
        album = album,
        linkedId = id,
        linkedBegin = begin,
    )

    companion object {
        const val BEFORE_MS = 15 * 60_000L
        const val AFTER_MS = 30 * 60_000L
    }
}

/** Works only when the Kalendář app is installed. Both sides check who is calling by the signing key. */
object CalendarLink {
    private const val PACKAGE = "cz.calendar"
    private const val AUTHORITY = "cz.calendar.link"
    private const val CERT = "8B48624FEFF248DEBB0906A02E0255030B3FE85CE5D2C2420FA653CB2ED81ABE"
    const val ACTION_SHOW = "com.justsomepren.gallery.action.SHOW_PHOTOS"

    fun installed(context: Context) = context.packageManager.resolveContentProvider(AUTHORITY, 0) != null

    fun isCalendar(context: Context, pkg: String?) =
        pkg == PACKAGE && context.packageManager.hasSigningCertificate(
            pkg, CERT.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), PackageManager.CERT_INPUT_SHA256,
        )

    /** Personal events between two epoch days. Null when the calendar cannot be read right now. */
    fun events(context: Context, from: Long, to: Long): List<CalendarItem>? {
        if (!installed(context)) return emptyList()
        val uri = Uri.parse("content://$AUTHORITY/events").buildUpon()
            .appendQueryParameter("from", "$from")
            .appendQueryParameter("to", "$to")
            .build()
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                buildList { while (c.moveToNext()) add(readItem(c)) }
            }
        }.getOrNull()
    }

    private fun readItem(c: Cursor) = CalendarItem(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        begin = c.getLong(c.getColumnIndexOrThrow("begin")),
        end = c.getLong(c.getColumnIndexOrThrow("end")),
        allDay = c.getInt(c.getColumnIndexOrThrow("all_day")) == 1,
        firstDay = c.getLong(c.getColumnIndexOrThrow("first_day")),
        lastDay = c.getLong(c.getColumnIndexOrThrow("last_day")),
        title = c.getString(c.getColumnIndexOrThrow("title")).orEmpty(),
        color = c.getInt(c.getColumnIndexOrThrow("color")),
        vacation = c.getInt(c.getColumnIndexOrThrow("vacation")) == 1,
        album = c.getString(c.getColumnIndexOrThrow("album"))?.ifBlank { null },
        account = c.getString(c.getColumnIndexOrThrow("account"))?.ifBlank { null },
        autoSend = c.getInt(c.getColumnIndexOrThrow("auto_send")) == 1,
    )

    /** Brings the vacations into the events, so they show in the calendar and send automatically. */
    fun sync(context: Context, from: Long = today() - 400, to: Long = today() + 400): List<CalendarItem>? {
        val items = events(context, from, to) ?: return null
        store(context, from, to, items)
        return items
    }

    /** Keeps the vacations among [items], read between [from] and [to], as events of this app. */
    fun store(context: Context, from: Long, to: Long, items: List<CalendarItem>) {
        val prefs = Prefs.get(context)
        prefs.syncLinked(from, to, items.filter { it.vacation }.map { it.toEvent(prefs.accounts.value) })
    }

    /** The Kalendář app says a vacation changed. */
    fun onCalendarChanged(context: Context) {
        val prefs = Prefs.get(context)
        val before = prefs.events.value
        sync(context)
        if (prefs.events.value != before) {
            runCatching { prefs.collectAuto(MediaRepository(context).loadAll()) }
            UploadWorker.schedule(context)
        }
    }

    fun openEvent(context: Context, id: Long, begin: Long?) {
        val intent = Intent("cz.calendar.action.OPEN_EVENT").setPackage(PACKAGE)
            .putExtra("event_id", id)
            .apply { if (begin != null) putExtra("instance_begin", begin) }
        runCatching { context.startActivity(intent) }
            .onFailure { Toast.makeText(context, "Kalendář se nepodařilo otevřít", Toast.LENGTH_SHORT).show() }
    }
}

/** Days where photos belong to a calendar event; vacations already show as bars. */
fun pairedDays(items: List<CalendarItem>, byDay: Map<Long, List<MediaItem>>): Set<Long> =
    items.filterNot { it.vacation }.flatMap { item ->
        (item.firstDay..item.lastDay).filter { day -> byDay[day].orEmpty().filterBy(item.source, emptyList()).isNotEmpty() }
    }.toSet()

/** Median of the GPS positions, so one photo from elsewhere does not move the place. */
fun medianPlace(points: List<DoubleArray>): DoubleArray? {
    if (points.isEmpty()) return null
    fun median(values: List<Double>) = values.sorted()[values.size / 2]
    return doubleArrayOf(median(points.map { it[0] }), median(points.map { it[1] }))
}

/** Photos, Google accounts and the place of an event, for the Kalendář app. */
class LinkProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val context = context ?: return null
        if (!CalendarLink.isCalendar(context, callingPackage)) return null
        return runCatching {
            when (uri.pathSegments.firstOrNull()) {
                "accounts" -> MatrixCursor(arrayOf("email")).apply {
                    Prefs.get(context).accounts.value.forEach { addRow(arrayOf(it)) }
                }
                "photos" -> MatrixCursor(arrayOf("id", "date")).apply {
                    photos(context, uri).forEach { addRow(arrayOf(it.id, it.date)) }
                }
                // "from-to,from-to,...": one count per window, the photos are read only once
                "counts" -> MatrixCursor(arrayOf("count")).apply {
                    val media = MediaRepository(context).loadAll()
                    uri.getQueryParameter("windows").orEmpty().split(",").filter { it.isNotBlank() }.forEach { window ->
                        val (from, to) = window.split("-").map { it.toLong() }
                        addRow(arrayOf(media.filterBy(Source.Span("", from, to), emptyList()).size))
                    }
                }
                // The best photo of each day between two epoch days, for the calendar month view
                "covers" -> MatrixCursor(arrayOf("day", "id")).apply {
                    val from = uri.getQueryParameter("from")!!.toLong()
                    val to = uri.getQueryParameter("to")!!.toLong()
                    MediaRepository(context).loadAll().filter { it.isCameraShot && it.day in from..to }
                        .groupBy { it.day }
                        .forEach { (day, items) -> addRow(arrayOf(day, items.cover()!!.id)) }
                }
                "place" -> MatrixCursor(arrayOf("lat", "lon")).apply {
                    place(context, photos(context, uri))?.let { addRow(arrayOf(it[0], it[1])) }
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun photos(context: Context, uri: Uri): List<MediaItem> {
        val from = uri.getQueryParameter("from")?.toLongOrNull() ?: return emptyList()
        val to = uri.getQueryParameter("to")?.toLongOrNull() ?: return emptyList()
        return MediaRepository(context).loadAll().filterBy(Source.Span("", from, to), emptyList()).sortedBy { it.date }
    }

    /** Reads the GPS of at most 20 photos spread over the event; most photos have none. */
    private fun place(context: Context, items: List<MediaItem>): DoubleArray? {
        val photos = items.filterNot { it.isVideo }
        if (photos.isEmpty()) return null
        val step = (photos.size / 20).coerceAtLeast(1)
        val repo = MediaRepository(context)
        return medianPlace(photos.filterIndexed { index, _ -> index % step == 0 }.take(20).mapNotNull(repo::location))
    }

    /** Instead of an open broadcast, so only the Kalendář app can make the gallery read again. */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        if (method == "calendar_changed" && CalendarLink.isCalendar(context, callingPackage)) {
            thread { CalendarLink.onCalendarChanged(context) }
        }
        return null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        if (!CalendarLink.isCalendar(context, callingPackage)) return null
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        val file = File(context.cacheDir, "link/$id.jpg")
        if (!file.exists()) {
            val bitmap = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .firstNotNullOfOrNull {
                    runCatching { context.contentResolver.loadThumbnail(ContentUris.withAppendedId(it, id), Size(256, 256), null) }.getOrNull()
                } ?: return null
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
}

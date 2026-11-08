package com.justsomepren.gallery.walkthrough

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.justsomepren.gallery.data.EVENT_COLORS
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.Prefs
import com.justsomepren.gallery.ui.ImageCache
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Runs before the activity starts: clean prefs, permissions, WorkManager, fake pictures. */
class AppSetupRule(private val prepare: (Prefs) -> Unit = {}) : ExternalResource() {
    val app: Application get() = ApplicationProvider.getApplicationContext()

    override fun before() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        app.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        File(app.filesDir, "sent.txt").delete()
        Prefs.reset()
        ImageCache.clear()
        ImageCache.fake = ::fakePhoto
        com.justsomepren.gallery.data.TripNamer.fakeStatus = com.google.mlkit.genai.common.FeatureStatus.UNAVAILABLE
        prepare(Prefs.get(app))
    }

    override fun after() {
        ImageCache.fake = null
    }
}

object Fixtures {
    val today: LocalDate = LocalDate.now()
    val photos: List<MediaItem> = (0L until 90L).map { photo(it, today.minusDays(it / 3)) }

    val trip = Event(
        id = "trip",
        name = "Dovca Praha",
        color = EVENT_COLORS[5],
        startDay = today.minusDays(4).toEpochDay(),
        endDay = today.plusDays(3).toEpochDay(),
        autoSend = true,
        account = "shared@gmail.com",
        album = "Dovca Praha",
    )
    val birthday = Event(
        id = "bday",
        name = "Narozeniny",
        color = EVENT_COLORS[1],
        startDay = today.minusDays(12).toEpochDay(),
        endDay = today.minusDays(11).toEpochDay(),
    )

    fun photo(id: Long, date: LocalDate): MediaItem {
        val millis = date.atTime(9 + (id % 10).toInt(), 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return MediaItem(
            id = id,
            uri = Uri.parse("content://media/external/images/media/$id"),
            mime = if (id % 11 == 5L) "video/mp4" else "image/jpeg",
            isVideo = id % 11 == 5L,
            date = millis,
            dateTaken = millis,
            bucketId = if (id % 7 == 0L) 2 else 1,
            bucketName = if (id % 7 == 0L) "Screenshots" else "Camera",
            isFavorite = id % 9 == 2L,
            name = "PXL_$id.jpg",
            width = 4000,
            height = 3000,
            size = 3_000_000,
            relativePath = if (id % 7 == 0L) "Pictures/Screenshots/" else "DCIM/Camera/",
        )
    }
}

fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

fun fakePhoto(uri: Uri): Bitmap {
    val id = uri.lastPathSegment!!.toInt()
    val bitmap = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawColor(Color.HSVToColor(floatArrayOf((id * 37 % 360).toFloat(), 0.4f, 0.9f)))
    return bitmap
}

/** Robolectric starts on mobile data; worker tests that are not about the network run on Wi-Fi. */
@Suppress("DEPRECATION")
fun onWifi(context: android.content.Context) {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
    val wifi = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
        android.net.NetworkInfo.DetailedState.CONNECTED,
        android.net.ConnectivityManager.TYPE_WIFI,
        0,
        true,
        android.net.NetworkInfo.State.CONNECTED,
    )
    org.robolectric.Shadows.shadowOf(cm).setActiveNetworkInfo(wifi)
}

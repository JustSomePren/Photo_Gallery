package com.justsomepren.gallery.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.EVENT_COLORS
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.Prefs
import com.justsomepren.gallery.data.QueueItem
import com.justsomepren.gallery.data.Source
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Starts the real app with made-up photos and saves each screen as a PNG
 * to app/build/outputs/roborazzi, to check the layout without a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-night-xxhdpi")
class AppScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val today = LocalDate.now()
    private val photos = (0L until 90L).map { photo(it, today.minusDays(it / 3)) }

    private val trip = Event(
        name = "Dovča Praha",
        color = EVENT_COLORS[5],
        startDay = today.minusDays(4).toEpochDay(),
        endDay = today.withDayOfMonth(today.lengthOfMonth()).toEpochDay(),
        autoSend = true,
        account = "sharedxxo@gmail.com",
        album = "Dovča Praha",
    )
    private val birthday = Event(
        name = "Narozeniny",
        color = EVENT_COLORS[1],
        startDay = today.minusDays(12).toEpochDay(),
        endDay = today.minusDays(11).toEpochDay(),
    )

    private lateinit var vm: GalleryViewModel

    @Before
    fun setUp() {
        runCatching { WorkManager.initialize(app, Configuration.Builder().build()) }
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        app.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        File(app.filesDir, "sent.txt").delete()
        Prefs.reset()
        ImageCache.fake = ::fakePhoto

        val prefs = Prefs.get(app)
        prefs.addAccount("sharedxxo@gmail.com")
        prefs.addAccount("filip@gmail.com")
        prefs.saveEvent(trip)
        prefs.saveEvent(birthday)
        prefs.enqueue(photos.take(12).map { QueueItem.of(it, "sharedxxo@gmail.com", "Dovča Praha") })

        vm = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]
        vm.loadMedia = { photos }
    }

    @After
    fun tearDown() {
        ImageCache.fake = null
    }

    private fun showApp() {
        compose.setContent { GalleryApp(vm) }
        // Photos load on a background thread; let the main thread pick up the result.
        compose.waitUntil(30_000) {
            shadowOf(Looper.getMainLooper()).idle()
            vm.media.value != null
        }
        compose.waitForIdle()
    }

    /** Dialogs with a text field keep a blinking cursor, so the screen never gets fully idle. */
    private fun showDialog(open: () -> Unit) {
        compose.mainClock.autoAdvance = false
        open()
        repeat(5) {
            compose.mainClock.advanceTimeBy(200)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun capture(name: String) = captureScreenRoboImage("build/outputs/roborazzi/$name.png")

    @Test
    fun photos() {
        showApp()
        capture("1_fotky")
    }

    @Test
    fun photosWhileScrolling() {
        showApp()
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.45f) }
        compose.mainClock.advanceTimeBy(300)
        capture("2_fotky_posouvani")
    }

    @Test
    fun eventEditor() {
        showApp()
        showDialog { vm.editingEvent = trip }
        capture("3_editor_udalosti")
    }

    @Test
    fun newEvent() {
        showApp()
        showDialog { vm.newEvent() }
        capture("4_nova_udalost")
    }

    @Test
    fun sending() {
        showApp()
        vm.tab = 3
        compose.waitForIdle()
        capture("5_odesilani")
    }

    @Test
    fun calendar() {
        showApp()
        vm.prefs.setCalendarView(true)
        compose.waitForIdle()
        capture("6_kalendar")
    }

    @Test
    fun eventPhotos() {
        showApp()
        vm.stack.add(Screen.Grid(trip.source))
        compose.waitForIdle()
        capture("7_fotky_udalosti")
    }

    @Test
    fun viewer() {
        showApp()
        vm.stack.add(Screen.Viewer(Source.All, photos[1].id))
        compose.waitForIdle()
        capture("8_prohlizec")
    }

    @Test
    fun albums() {
        showApp()
        vm.prefs.createAlbum("To nejlepší", photos.take(8))
        vm.tab = 2
        compose.waitForIdle()
        capture("9_alba")
    }

    private fun photo(id: Long, date: LocalDate): MediaItem {
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
            relativePath = "DCIM/Camera/",
        )
    }

    /** A simple "landscape": sky, sun and hills in a color that differs per photo. */
    private fun fakePhoto(uri: Uri): Bitmap {
        val id = uri.lastPathSegment!!.toInt()
        val size = 300
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val hue = (id * 37 % 360).toFloat()
        canvas.drawColor(Color.HSVToColor(floatArrayOf(hue, 0.35f, 0.95f)))
        paint.color = Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.25f, 1f))
        canvas.drawCircle(size * 0.7f, size * 0.3f, size * 0.12f, paint)
        paint.color = Color.HSVToColor(floatArrayOf((hue + 120) % 360, 0.6f, 0.55f))
        canvas.drawOval(-size * 0.3f, size * 0.55f, size * 0.9f, size * 1.4f, paint)
        paint.color = Color.HSVToColor(floatArrayOf((hue + 150) % 360, 0.7f, 0.4f))
        canvas.drawOval(size * 0.3f, size * 0.65f, size * 1.5f, size * 1.5f, paint)
        return bitmap
    }
}

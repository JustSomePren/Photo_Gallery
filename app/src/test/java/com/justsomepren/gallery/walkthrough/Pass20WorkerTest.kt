package com.justsomepren.gallery.walkthrough

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.justsomepren.gallery.data.PhotosApi
import com.justsomepren.gallery.data.Prefs
import com.justsomepren.gallery.data.QueueItem
import com.justsomepren.gallery.data.UploadWorker
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Bug-hunt pass 20: the battery threshold while a long queue is going up on Wi-Fi. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Pass20WorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val account = "me@gmail.com"
    private lateinit var prefs: Prefs
    private val savedChunk = PhotosApi.CHUNK

    private val started = mutableListOf<String>()
    private val created = mutableListOf<String>()

    /** Runs when the first file has gone up, e.g. the battery dropping meanwhile. */
    private var afterFirstFile: () -> Unit = {}

    @Before
    fun setUp() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "sent.txt").delete()
        Prefs.reset()
        prefs = Prefs.get(context)
        prefs.addAccount(account)
        battery(level = 75, plugged = false)
        PhotosApi.CHUNK = 1024
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path == "/uploads" && request.getHeader("X-Goog-Upload-Content-Type") == BUSY ->
                        MockResponse().setResponseCode(503)
                    path == "/uploads" -> {
                        started += request.getHeader("X-Goog-Upload-Raw-Size").orEmpty()
                        MockResponse().addHeader("X-Goog-Upload-URL", server.url("/session/${started.size}").toString())
                    }
                    path.startsWith("/session/") -> {
                        request.body.readByteArray()
                        val n = path.substringAfterLast('/')
                        if (n == "1") afterFirstFile()
                        MockResponse().setBody("TOKEN$n")
                    }
                    path == "/mediaItems:batchCreate" -> {
                        val items = JSONObject(request.body.readUtf8()).getJSONArray("newMediaItems")
                        val tokens = (0 until items.length()).map { items.getJSONObject(it).getJSONObject("simpleMediaItem").getString("uploadToken") }
                        created += tokens
                        val results = JSONArray(tokens.map { JSONObject().put("uploadToken", it).put("mediaItem", JSONObject().put("id", "m")) })
                        MockResponse().setBody(JSONObject().put("newMediaItemResults", results).toString())
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        PhotosApi.baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() {
        server.shutdown()
        PhotosApi.baseUrl = "https://photoslibrary.googleapis.com/v1"
        PhotosApi.CHUNK = savedChunk
    }

    private fun battery(level: Int, plugged: Boolean) = context.sendStickyBroadcast(
        Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_LEVEL, level)
            .putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_PLUGGED, if (plugged) BatteryManager.BATTERY_PLUGGED_AC else 0),
    )

    /** Small one-piece files, like most photos of a trip; [busy] ones get a passing server error. */
    private fun photos(count: Int, busy: Set<Int> = emptySet()): List<QueueItem> = (1..count).map { n ->
        val file = File(context.cacheDir, "PXL_$n.jpg").apply { writeBytes(ByteArray(500 + n) { it.toByte() }) }
        QueueItem("PXL_$n.jpg|$n", Uri.fromFile(file).toString(), "PXL_$n.jpg", if (n in busy) BUSY else "image/jpeg", account, null)
    }.also { prefs.enqueue(it) }

    private fun runCatchingRetry(items: List<QueueItem>) = runCatching { run(items, now = false) }

    private fun run(items: List<QueueItem>, now: Boolean) {
        val builder = TestListenableWorkerBuilder<UploadWorker>(context)
        if (now) builder.setInputData(workDataOf("now" to true))
        val worker = builder.build()
        UploadWorker::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(worker, prefs)
        UploadWorker::class.java.getDeclaredMethod("send", PhotosApi::class.java, String::class.java, List::class.java)
            .apply { isAccessible = true }
            .invoke(worker, PhotosApi(context, "token"), account, items)
    }

    /** Control: "Odeslat hned" ignores the battery, so all photos go up even when it drops meanwhile. */
    @Test
    fun sendNowKeepsGoingWhenBatteryDrops() {
        val items = photos(5)
        afterFirstFile = { battery(level = 50, plugged = false) }
        run(items, now = true)
        assertEquals(5, created.size)
        assertEquals(0, prefs.queue.value.size)
    }

    /** Control: on the charger nothing stops. */
    @Test
    fun chargingKeepsGoingBelowThreshold() {
        battery(level = 50, plugged = true)
        val items = photos(5)
        run(items, now = false)
        assertEquals(5, created.size)
    }

    /**
     * Home on Wi-Fi with 75 % battery (threshold 70 %), not charging, after a trip: the ordinary run starts.
     * The battery threshold is checked only once, when the run starts; the run then keeps uploading the whole
     * queue (hundreds of photos and long videos, often for an hour) while the battery drops far below the
     * threshold, although the app promises to send below it only while charging.
     */
    @Test
    fun ordinaryRunStopsWhenBatteryDropsBelowThreshold() {
        val items = photos(5)
        afterFirstFile = { battery(level = 50, plugged = false) }
        // The run stops with LowBattery; the worker then waits for the charger.
        val stop = runCatching { run(items, now = false) }.exceptionOrNull()
        assertEquals("LowBattery", (stop as? java.lang.reflect.InvocationTargetException)?.targetException?.javaClass?.simpleName)
        assertEquals("files started after the battery fell below the threshold", 1, started.size)
        assertEquals("the rest waits in the queue for the charger", 4, prefs.queue.value.size)
    }

    /**
     * Google answers one photo of a batch with a passing server error (503) for a while. The error aborts the
     * whole run: photos uploaded before it in the same batch are never added (their finished uploads are thrown
     * away and go up again in full next run), and the photos after it are not even tried. Each retry repeats
     * this, so as long as that one file keeps failing, nothing behind it is ever sent and the photos before it
     * are uploaded again and again.
     */
    @Test
    fun passingErrorOfOnePhotoDoesNotHoldBackTheOthers() {
        val items = photos(3, busy = setOf(2))
        repeat(2) { runCatchingRetry(items.mapNotNull { i -> prefs.queue.value.firstOrNull { it.sentKey == i.sentKey } }) }
        assertEquals("each other photo uploaded once (sizes 501 and 503)", listOf("501", "503"), started)
        assertEquals(listOf("PXL_2.jpg"), prefs.queue.value.map { it.name })
    }

    private companion object {
        const val BUSY = "image/x-busy"
    }
}

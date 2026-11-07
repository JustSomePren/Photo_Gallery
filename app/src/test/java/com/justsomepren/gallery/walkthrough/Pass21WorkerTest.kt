package com.justsomepren.gallery.walkthrough

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.File

/** Bug-hunt pass 21: turning the mobile-data switch off while photos go up over mobile data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Pass21WorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val account = "me@gmail.com"
    private lateinit var prefs: Prefs
    private val savedChunk = PhotosApi.CHUNK
    private val created = mutableListOf<String>()
    private var sessions = 0
    private var afterFirstFile: () -> Unit = {}

    @Before
    fun setUp() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "sent.txt").delete()
        Prefs.reset()
        prefs = Prefs.get(context)
        prefs.addAccount(account)
        prefs.setMobileData(true)
        context.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 90)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_PLUGGED, 0),
        )
        // Only mobile data, e.g. on a trip.
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
        PhotosApi.CHUNK = 1024
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path == "/uploads" -> {
                        sessions++
                        MockResponse().addHeader("X-Goog-Upload-URL", server.url("/session/$sessions").toString())
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

    private fun photos(count: Int): List<QueueItem> = (1..count).map { n ->
        val file = File(context.cacheDir, "PXL_$n.jpg").apply { writeBytes(ByteArray(500 + n) { it.toByte() }) }
        QueueItem("PXL_$n.jpg|$n", Uri.fromFile(file).toString(), "PXL_$n.jpg", "image/jpeg", account, null)
    }.also { prefs.enqueue(it) }

    private fun run(items: List<QueueItem>, now: Boolean) = runCatching {
        val builder = TestListenableWorkerBuilder<UploadWorker>(context)
        if (now) builder.setInputData(workDataOf("now" to true))
        val worker = builder.build()
        UploadWorker::class.java.getDeclaredMethod("send", PhotosApi::class.java, String::class.java, List::class.java)
            .apply { isAccessible = true }
            .invoke(worker, PhotosApi(context, "token"), account, items)
    }

    /** Control: with the switch on, a run over mobile data sends everything. */
    @Test
    fun mobileDataSwitchOnSendsAll() {
        val items = photos(5)
        run(items, now = false)
        assertEquals(5, created.size)
    }

    /** Control: "Odeslat hned" goes on over mobile data whatever the switch says. */
    @Test
    fun sendNowIgnoresSwitch() {
        val items = photos(5)
        afterFirstFile = { prefs.setMobileData(false) }
        run(items, now = true)
        assertEquals(5, created.size)
    }

    /**
     * An auto-send event is running and mobile data is allowed; the hourly (periodic) run is uploading a long
     * queue over mobile data. The user turns "Posílat i přes mobilní data" off (e.g. roaming). setMobileData only
     * re-schedules: the periodic work is updated with ExistingPeriodicWorkPolicy.UPDATE, which WorkManager applies
     * to the NEXT run only (UpdateResult.APPLIED_FOR_NEXT_RUN), so the running one keeps its old "any network"
     * constraint; and the worker itself never checks the switch or the network. The rest of the queue goes up over
     * mobile data although the switch is off.
     */
    @Test
    fun ordinaryRunStopsUsingMobileDataWhenSwitchTurnedOff() {
        val items = photos(5)
        afterFirstFile = { prefs.setMobileData(false) }
        run(items, now = false)
        assertEquals("files sent after the switch was turned off", 1, created.size)
        assertEquals(4, prefs.queue.value.size)
    }
}

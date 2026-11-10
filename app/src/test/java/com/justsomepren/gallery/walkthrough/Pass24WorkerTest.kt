package com.justsomepren.gallery.walkthrough

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.InvocationTargetException

/** Bug-hunt pass 24: per-photo answers of batchCreate and an expired login in the middle of a batch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Pass24WorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val account = "me@gmail.com"
    private lateinit var prefs: Prefs
    private val savedChunk = PhotosApi.CHUNK

    private val started = mutableListOf<String>()
    private val created = mutableListOf<String>()

    /** batchCreate answer per file name: null = accepted, otherwise the gRPC status code. */
    private var statusFor: (String) -> Int? = { null }

    /** File names whose chunk upload answers 401 (login expired). */
    private var expiredFor = setOf<String>()

    @Before
    fun setUp() {
        onWifi(context)
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "sent.txt").delete()
        Prefs.reset()
        prefs = Prefs.get(context)
        prefs.addAccount(account)
        PhotosApi.CHUNK = 1024
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    path == "/uploads" -> {
                        val name = request.getHeader("X-Goog-Upload-Raw-Size").orEmpty()
                        started += name
                        MockResponse().addHeader("X-Goog-Upload-URL", server.url("/session/${started.size}").toString())
                    }
                    path.startsWith("/session/") -> {
                        request.body.readByteArray()
                        val n = path.substringAfterLast('/').toInt()
                        if ("PXL_$n.jpg" in expiredFor) MockResponse().setResponseCode(401)
                        else MockResponse().setBody("TOKEN$n")
                    }
                    path == "/mediaItems:batchCreate" -> {
                        val items = JSONObject(request.body.readUtf8()).getJSONArray("newMediaItems")
                        val results = JSONArray()
                        for (i in 0 until items.length()) {
                            val simple = items.getJSONObject(i).getJSONObject("simpleMediaItem")
                            val token = simple.getString("uploadToken")
                            val code = statusFor(simple.getString("fileName"))
                            if (code == null) {
                                created += simple.getString("fileName")
                                results.put(JSONObject().put("uploadToken", token).put("mediaItem", JSONObject().put("id", "m")))
                            } else {
                                results.put(JSONObject().put("uploadToken", token).put("status", JSONObject().put("code", code)))
                            }
                        }
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

    private fun photos(count: Int, mime: (Int) -> String = { "image/jpeg" }): List<QueueItem> = (1..count).map { n ->
        val file = File(context.cacheDir, "PXL_$n.jpg").apply { writeBytes(ByteArray(500 + n) { it.toByte() }) }
        QueueItem("PXL_$n.jpg|$n", Uri.fromFile(file).toString(), "PXL_$n.jpg", mime(n), account, null)
    }.also { prefs.enqueue(it) }

    private fun send(items: List<QueueItem>): Throwable? {
        val worker = TestListenableWorkerBuilder<UploadWorker>(context).build()
        UploadWorker::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(worker, prefs)
        return try {
            UploadWorker::class.java.getDeclaredMethod("send", PhotosApi::class.java, String::class.java, List::class.java)
                .apply { isAccessible = true }
                .invoke(worker, PhotosApi(context, "token"), account, items)
            null
        } catch (e: InvocationTargetException) {
            e.targetException
        }
    }

    /** One accepted, one passing error (UNAVAILABLE), one real refusal (INVALID_ARGUMENT) in the same batch. */
    @Test
    fun batchCreateAnswersArePerPhoto() {
        statusFor = { name -> when (name) { "PXL_2.jpg" -> 14; "PXL_3.jpg" -> 3; else -> null } }
        val items = photos(3)
        assertEquals(null, send(items))
        assertEquals(listOf("PXL_1.jpg"), created)
        assertTrue(prefs.isSent(items[0]))
        // The id Google gave is kept, for checking later before freeing space.
        assertEquals("m", prefs.cloudCopies(items[0].key).single().mediaItemId)
        assertEquals("passing error waits for the next run", listOf("PXL_2.jpg"), prefs.queue.value.map { it.name })
        assertEquals("only the refusal counts as failed", listOf("PXL_3.jpg"), prefs.failed.value.map { it.name })

        // Next run: Google is fine again, the waiting photo goes up and nothing is sent twice.
        statusFor = { null }
        assertEquals(null, send(prefs.queue.value))
        assertEquals(listOf("PXL_1.jpg", "PXL_2.jpg"), created)
        assertTrue(prefs.queue.value.isEmpty())
    }

    /** The login expires in the middle of a batch: the run stops for a new token, nothing counts as refused. */
    @Test
    fun expiredLoginMidBatchKeepsRestQueuedAndAddsWhatWentUp() {
        expiredFor = setOf("PXL_2.jpg")
        val items = photos(3)
        val stop = send(items)
        assertTrue("run should end for a retry, got $stop", stop is java.io.IOException)
        assertEquals("the photo uploaded before the 401 is still added", listOf("PXL_1.jpg"), created)
        assertEquals(listOf("PXL_2.jpg", "PXL_3.jpg"), prefs.queue.value.map { it.name })
        assertTrue(prefs.failed.value.isEmpty())

        expiredFor = emptySet()
        assertEquals(null, send(prefs.queue.value))
        assertEquals(listOf("PXL_1.jpg", "PXL_2.jpg", "PXL_3.jpg"), created)
    }

    /** A video whose batchCreate hits a passing error stays queued, not failed. */
    @Test
    fun videoWithPassingCreateErrorStaysQueued() {
        statusFor = { name -> if (name == "PXL_1.jpg") 13 else null }
        val items = photos(2) { n -> if (n == 1) "video/mp4" else "image/jpeg" }
        assertEquals(null, send(items))
        assertEquals(listOf("PXL_2.jpg"), created)
        assertEquals(listOf("PXL_1.jpg"), prefs.queue.value.map { it.name })
        assertTrue(prefs.failed.value.isEmpty())
    }
}

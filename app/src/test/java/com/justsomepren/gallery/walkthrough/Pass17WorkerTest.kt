package com.justsomepren.gallery.walkthrough

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.justsomepren.gallery.data.PhotosApi
import com.justsomepren.gallery.data.Prefs
import com.justsomepren.gallery.data.QueueItem
import androidx.work.testing.TestListenableWorkerBuilder
import com.justsomepren.gallery.data.UploadWorker
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * Bug-hunt pass 17: resumable uploads that continue in the next run, against a stand-in for Google
 * that keeps what it received per upload session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Pass17WorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val account = "me@gmail.com"
    private lateinit var prefs: Prefs

    private val received = mutableMapOf<String, Int>()
    private var sessions = 0
    private var failNextChunk = false
    private val created = mutableListOf<String>()
    private val savedChunk = PhotosApi.CHUNK

    @Before
    fun setUp() {
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
                        val id = "/session${++sessions}"
                        received[id] = 0
                        MockResponse().addHeader("X-Goog-Upload-URL", server.url(id).toString())
                    }
                    path.startsWith("/session") -> session(path, request)
                    path == "/mediaItems:batchCreate" -> batchCreate(JSONObject(request.body.readUtf8()))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        PhotosApi.baseUrl = server.url("/").toString().trimEnd('/')
    }

    private fun session(path: String, request: RecordedRequest): MockResponse {
        val command = request.getHeader("X-Goog-Upload-Command").orEmpty()
        val bytes = request.body.readByteArray()
        if (command == "query") {
            return MockResponse().addHeader("X-Goog-Upload-Status", "active")
                .addHeader("X-Goog-Upload-Size-Received", received.getValue(path).toString())
        }
        if (failNextChunk && received.getValue(path) > 0) {
            failNextChunk = false
            return MockResponse().setResponseCode(503)
        }
        received[path] = received.getValue(path) + bytes.size
        return MockResponse().setBody(if ("finalize" in command) "TOKEN$path" else "")
    }

    private fun batchCreate(body: JSONObject): MockResponse {
        val items = body.getJSONArray("newMediaItems")
        val tokens = (0 until items.length()).map { items.getJSONObject(it).getJSONObject("simpleMediaItem").getString("uploadToken") }
        created += tokens
        val results = JSONArray(tokens.map { JSONObject().put("uploadToken", it).put("mediaItem", JSONObject().put("id", "m-$it")) })
        return MockResponse().setBody(JSONObject().put("newMediaItemResults", results).toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
        PhotosApi.baseUrl = "https://photoslibrary.googleapis.com/v1"
        PhotosApi.CHUNK = savedChunk
    }

    private fun file(name: String, size: Int) = File(context.cacheDir, name).apply { writeBytes(ByteArray(size) { it.toByte() }) }

    private fun queued(name: String, size: Int): QueueItem {
        val item = QueueItem("$name|1", Uri.fromFile(file(name, size)).toString(), name, "image/jpeg", account, null)
        prefs.enqueue(listOf(item))
        return item
    }

    @Suppress("UNCHECKED_CAST")
    private fun worker(): UploadWorker {
        val worker = TestListenableWorkerBuilder<UploadWorker>(context).build()
        UploadWorker::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(worker, prefs)
        return worker
    }

    private fun run(items: List<QueueItem>): Throwable? {
        val send = UploadWorker::class.java.getDeclaredMethod("send", PhotosApi::class.java, String::class.java, List::class.java)
            .apply { isAccessible = true }
        return try {
            send.invoke(worker(), PhotosApi(context, "token"), account, items)
            null
        } catch (e: InvocationTargetException) {
            e.targetException
        }
    }

    /** Control: an upload cut off after the first piece continues in the next run and finishes. */
    @Test
    fun interruptedUploadContinuesNextRun() {
        val photo = queued("PXL_big.jpg", 3000)
        failNextChunk = true
        // A passing error on one file no longer ends the run; the file waits in the queue for the next run.
        assertTrue(run(listOf(photo)) == null)
        assertTrue(!prefs.isSent(photo) && prefs.queue.value.isNotEmpty())
        assertTrue(run(prefs.queue.value) == null)
        assertTrue(prefs.isSent(photo))
        assertTrue("restarted instead of continuing", sessions == 1)
    }
}

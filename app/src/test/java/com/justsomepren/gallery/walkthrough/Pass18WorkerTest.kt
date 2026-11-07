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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Bug-hunt pass 18: what the worker does when the user changes their mind in the middle of a long upload. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Pass18WorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val account = "me@gmail.com"
    private lateinit var prefs: Prefs
    private val savedChunk = PhotosApi.CHUNK

    private var chunks = 0
    private var chunksAfterChange = 0
    private var changed = false
    private val created = mutableListOf<String>()

    /** Runs once the second piece of a file has arrived, like the user tapping in the app meanwhile. */
    private var onSecondChunk: () -> Unit = {}

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
                    path == "/uploads" -> MockResponse().addHeader("X-Goog-Upload-URL", server.url("/session").toString())
                    path == "/session" -> {
                        request.body.readByteArray()
                        if (changed) chunksAfterChange++
                        if (++chunks == 2) {
                            onSecondChunk()
                            changed = true
                        }
                        val command = request.getHeader("X-Goog-Upload-Command").orEmpty()
                        MockResponse().setBody(if ("finalize" in command) "TOKEN" else "")
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

    /** A long video: 20 pieces here, thousands for a real 4K video. */
    private fun video(): QueueItem {
        val file = File(context.cacheDir, "PXL_long.mp4").apply { writeBytes(ByteArray(20 * 1024) { it.toByte() }) }
        val item = QueueItem("PXL_long.mp4|1", Uri.fromFile(file).toString(), "PXL_long.mp4", "video/mp4", account, null)
        prefs.enqueue(listOf(item))
        return item
    }

    private fun run(items: List<QueueItem>) {
        val worker = TestListenableWorkerBuilder<UploadWorker>(context).build()
        UploadWorker::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(worker, prefs)
        UploadWorker::class.java.getDeclaredMethod("send", PhotosApi::class.java, String::class.java, List::class.java)
            .apply { isAccessible = true }
            .invoke(worker, PhotosApi(context, "token"), account, items)
    }

    /** Control: nothing changes, the video is uploaded in pieces and added once. */
    @Test
    fun longVideoIsSentOnce() {
        val item = video()
        run(listOf(item))
        assertEquals(20, chunks)
        assertEquals(listOf("TOKEN"), created)
        assertTrue(prefs.isSent(item))
    }

    /**
     * The user removes the account while a long video is being uploaded to it. The video is not added to the
     * library, but the app keeps pushing the rest of the file to that account until the end (minutes of Wi-Fi
     * and battery for a real video, with the "Odesílám" notification still up).
     */
    @Test
    fun removingAccountStopsRunningUpload() {
        val item = video()
        onSecondChunk = { prefs.removeAccount(account) }
        run(listOf(item))
        assertTrue(created.isEmpty())
        assertEquals("pieces still sent to the removed account", 0, chunksAfterChange)
    }

    /** Same with "Vyprázdnit" in the Sending tab. */
    @Test
    fun clearingQueueStopsRunningUpload() {
        val item = video()
        onSecondChunk = { prefs.clearQueue() }
        run(listOf(item))
        assertTrue(created.isEmpty())
        assertEquals("pieces still sent after clearing the queue", 0, chunksAfterChange)
    }
}

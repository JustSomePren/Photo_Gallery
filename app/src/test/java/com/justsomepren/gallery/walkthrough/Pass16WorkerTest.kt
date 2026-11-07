package com.justsomepren.gallery.walkthrough

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
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

/**
 * Bug-hunt pass 16: the upload worker's sending logic against a stand-in for Google
 * (login cannot run here, so UploadWorker.send is called directly with a ready PhotosApi).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Pass16WorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val account = "me@gmail.com"
    private lateinit var prefs: Prefs

    /** Album ids Google knows; adding to any other one is refused like a deleted album. */
    private val liveAlbums = mutableSetOf<String>()
    private var nextAlbum = 0
    private var tokens = 0
    private val added = mutableListOf<Pair<String?, List<String>>>()

    @Before
    fun setUp() {
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "sent.txt").delete()
        Prefs.reset()
        prefs = Prefs.get(context)
        prefs.addAccount(account)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/uploads" -> MockResponse().addHeader("X-Goog-Upload-URL", server.url("/session").toString())
                "/session" -> {
                    request.body.readByteArray()
                    MockResponse().setBody("TOKEN-${++tokens}")
                }
                "/albums" -> {
                    val id = "NEW-${++nextAlbum}"
                    liveAlbums += id
                    MockResponse().setBody(JSONObject().put("id", id).toString())
                }
                "/mediaItems:batchCreate" -> batchCreate(JSONObject(request.body.readUtf8()))
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        PhotosApi.baseUrl = server.url("/").toString().trimEnd('/')
    }

    private fun batchCreate(body: JSONObject): MockResponse {
        val album = body.optString("albumId").ifEmpty { null }
        if (album != null && album !in liveAlbums) {
            return MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"status":"INVALID_ARGUMENT"}}""")
        }
        val items = body.getJSONArray("newMediaItems")
        val tokensIn = (0 until items.length()).map { items.getJSONObject(it).getJSONObject("simpleMediaItem").getString("uploadToken") }
        added += album to tokensIn
        val results = JSONArray(tokensIn.map { JSONObject().put("uploadToken", it).put("mediaItem", JSONObject().put("id", "m-$it")) })
        return MockResponse().setBody(JSONObject().put("newMediaItemResults", results).toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
        PhotosApi.baseUrl = "https://photoslibrary.googleapis.com/v1"
    }

    private fun queued(name: String, mime: String): QueueItem {
        val file = File(context.cacheDir, name).apply { writeBytes(ByteArray(5000) { it.toByte() }) }
        return QueueItem("$name|1", Uri.fromFile(file).toString(), name, mime, account, "Trip").also {
            prefs.enqueue(listOf(it))
        }
    }

    /** A worker without WorkManager around it; send() only needs the shared Prefs. */
    @Suppress("UNCHECKED_CAST")
    private fun worker(): UploadWorker {
        val unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = unsafeField.get(null)
        val worker = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, UploadWorker::class.java) as UploadWorker
        UploadWorker::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(worker, prefs)
        return worker
    }

    /** One run of the worker for this account; an IOException means WorkManager retries the run. */
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

    /** Control: a photo whose stored Google album was deleted goes into a new album on the next run. */
    @Test
    fun photoGoesToNewAlbumAfterStoredAlbumWasDeleted() {
        prefs.setAlbumId(account, "Trip", "OLD")
        val photo = queued("PXL_1.jpg", "image/jpeg")

        val first = run(listOf(photo))
        assertTrue("first run should be retried, got $first", first is java.io.IOException)
        assertEquals(null, prefs.albumId(account, "Trip"))

        assertEquals(null, run(prefs.queue.value))
        assertTrue(prefs.isSent(photo))
        assertTrue(prefs.failed.value.isEmpty())
        assertEquals("NEW-1", added.single().first)
    }

    /**
     * The same for a video: the user deleted the event's album in Google Photos. Google did not refuse the video,
     * only the old album id; the worker forgets the album and should send the video into a new one. Instead the
     * video is marked as refused/failed for the account, so the event never sends it again automatically.
     */
    @Test
    fun videoGoesToNewAlbumAfterStoredAlbumWasDeleted() {
        prefs.setAlbumId(account, "Trip", "OLD")
        val video = queued("PXL_2.mp4", "video/mp4")

        run(listOf(video))
        assertTrue("video was marked failed: ${prefs.failed.value}", prefs.failed.value.isEmpty())
        run(prefs.queue.value)
        assertTrue(prefs.isSent(video))
        assertEquals("NEW-1", added.single().first)
    }
}

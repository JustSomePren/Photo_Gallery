package com.justsomepren.gallery.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/** Google's resumable upload, played by a local server: big videos continue where they stopped. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ResumableUploadTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val received = ByteArrayOutputStream()
    private val offsets = mutableListOf<Long>()
    private var starts = 0
    private var pieces = 0
    private var failPiece = 0

    private val chunk = 256 * 1024
    private val video = Random(1).nextBytes(chunk * 3 + 1000)
    private lateinit var item: QueueItem

    @Before
    fun setUp() {
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.reset()
        val file = File(context.cacheDir, "VID_1.mp4").apply { writeBytes(video) }
        item = QueueItem("VID_1.mp4|1", Uri.fromFile(file).toString(), "VID_1.mp4", "video/mp4", "me@gmail.com", null)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/uploads" -> {
                    starts++
                    MockResponse().addHeader("X-Goog-Upload-URL", server.url("/session").toString())
                }
                "/session" -> session(request)
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        PhotosApi.baseUrl = server.url("/").toString().trimEnd('/')
        PhotosApi.CHUNK = chunk
    }

    private fun session(request: RecordedRequest): MockResponse {
        val command = request.getHeader("X-Goog-Upload-Command").orEmpty()
        if (command == "query") {
            return MockResponse()
                .addHeader("X-Goog-Upload-Status", "active")
                .addHeader("X-Goog-Upload-Size-Received", received.size().toString())
        }
        if (++pieces == failPiece) return MockResponse().setResponseCode(503)
        offsets += request.getHeader("X-Goog-Upload-Offset")!!.toLong()
        received.write(request.body.readByteArray())
        return MockResponse().setBody(if ("finalize" in command) "TOKEN" else "")
    }

    @After
    fun tearDown() {
        server.shutdown()
        PhotosApi.baseUrl = "https://photoslibrary.googleapis.com/v1"
        PhotosApi.CHUNK = 8 * 1024 * 1024
    }

    @Test
    fun uploadsInPiecesAndReturnsToken() {
        assertEquals("TOKEN", PhotosApi(context, "t").upload(item))
        assertArrayEquals(video, received.toByteArray())
        assertEquals(listOf(0L, chunk.toLong(), 2L * chunk, 3L * chunk), offsets)
        assertEquals(1, starts)
    }

    @Test
    fun fileChangedMeanwhileStartsOver() {
        val api = PhotosApi(context, "t")
        failPiece = 2
        assertTrue(runCatching { api.upload(item) }.isFailure)

        // Edited between two runs: the old session must not be continued with new bytes.
        java.io.File(Uri.parse(item.uri).path!!).appendBytes(ByteArray(10))
        received.reset()
        assertEquals("TOKEN", api.upload(item))
        assertEquals(2, starts)
        assertEquals(video.size + 10, received.size())
    }

    @Test
    fun continuesWhereItStoppedInTheNextRun() {
        val api = PhotosApi(context, "t")
        failPiece = 2
        assertTrue(runCatching { api.upload(item) }.isFailure)
        assertEquals(chunk, received.size())

        // Next run: same session, only the rest is sent.
        assertEquals("TOKEN", api.upload(item))
        assertEquals(1, starts)
        assertArrayEquals(video, received.toByteArray())
        assertEquals(null, Prefs.get(context).uploadSession(item.sentKey, video.size.toLong()))
    }
}

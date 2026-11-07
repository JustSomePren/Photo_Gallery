package com.justsomepren.gallery.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** "Uvolnit místo": only photos provably in Google Photos may be deleted from the phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeUpTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: Prefs
    private val server = MockWebServer()

    private fun photo(id: Long, size: Long = 1000, favorite: Boolean = false, path: String = "DCIM/Camera/") = MediaItem(
        id, Uri.parse("content://media/external/images/media/$id"), "image/jpeg", false, 1_000 * id, 1_000 * id,
        1, "Camera", favorite, "PXL_$id.jpg", 1, 1, size, path,
    )

    private fun sent(item: MediaItem, cloudId: String, size: Long = item.size) =
        prefs.markSent(listOf(QueueItem.of(item, "me@gmail.com", null)), mapOf("me@gmail.com|${item.key}" to cloudId), mapOf(item.key to size))

    @Before
    fun setUp() {
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "sent.txt").delete()
        Prefs.reset()
        prefs = Prefs.get(context)
        prefs.addAccount("me@gmail.com")
    }

    @After
    fun tearDown() {
        server.shutdown()
        PhotosApi.baseUrl = "https://photoslibrary.googleapis.com/v1"
    }

    @Test
    fun onlyUploadedUnchangedPhotosAreCandidates() {
        val uploaded = photo(1)
        val changedSince = photo(2, size = 2000)
        val neverSent = photo(3)
        val favorite = photo(4, favorite = true)
        sent(uploaded, "A1")
        sent(changedSince, "A2", size = 1500)
        sent(favorite, "A4")

        assertEquals(listOf(1L), freeUpCandidates(listOf(uploaded, changedSince, neverSent, favorite), prefs, keepFavorites = true).map { it.first.id })
        assertEquals(listOf(1L, 4L), freeUpCandidates(listOf(uploaded, changedSince, neverSent, favorite), prefs, keepFavorites = false).map { it.first.id })

        // Remembered after a restart.
        Prefs.reset()
        assertEquals("A1", Prefs.get(context).cloudCopies(uploaded.key).single().mediaItemId)
    }

    @Test
    fun onlyWhatGoogleConfirmsWithTheSameNameMayGo() {
        val a = photo(1)
        val b = photo(2)
        val c = photo(3)
        sent(a, "A1")
        sent(b, "A2")
        sent(c, "A3")
        val candidates = freeUpCandidates(listOf(a, b, c), prefs, keepFavorites = true)
        // A2 was deleted in Google Photos, A3 is another file now.
        val there = mapOf("A1" to "PXL_1.jpg", "A3" to "something_else.jpg")
        assertEquals(listOf(1L), confirmedInCloud(candidates, there).map { it.id })
    }

    @Test
    fun removedAccountGivesNoCandidates() {
        val a = photo(1)
        sent(a, "A1")
        prefs.removeAccount("me@gmail.com")
        assertTrue(freeUpCandidates(listOf(a), prefs, keepFavorites = false).isEmpty())
    }

    @Test
    fun videoStillProcessingAtGoogleDoesNotCount() {
        server.enqueue(
            MockResponse().setBody(
                """{"mediaItemResults": [
                    {"mediaItem": {"id": "V1", "filename": "PXL_1.mp4", "mediaMetadata": {"video": {"status": "PROCESSING"}}}},
                    {"mediaItem": {"id": "V2", "filename": "PXL_2.mp4", "mediaMetadata": {"video": {"status": "READY"}}}},
                    {"mediaItem": {"id": "V3", "filename": "PXL_3.mp4", "mediaMetadata": {"video": {"status": "FAILED"}}}}
                ]}""",
            ),
        )
        server.start()
        PhotosApi.baseUrl = server.url("/").toString().trimEnd('/')
        assertEquals(setOf("V2"), PhotosApi(context, "token").existing(listOf("V1", "V2", "V3")).keys)
    }

    @Test
    fun asksGoogleWhichItemsExist() {
        server.enqueue(
            MockResponse().setBody(
                """{"mediaItemResults": [
                    {"mediaItem": {"id": "A1", "filename": "PXL_1.jpg"}},
                    {"status": {"code": 5, "message": "not found"}}
                ]}""",
            ),
        )
        server.start()
        PhotosApi.baseUrl = server.url("/").toString().trimEnd('/')

        val there = PhotosApi(context, "token").existing(listOf("A1", "A2"))

        assertEquals(mapOf("A1" to "PXL_1.jpg"), there)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/mediaItems:batchGet?mediaItemIds=A1&mediaItemIds=A2", request.path)
        assertEquals("Bearer token", request.getHeader("Authorization"))
    }
}

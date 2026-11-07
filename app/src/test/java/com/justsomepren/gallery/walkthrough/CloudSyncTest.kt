package com.justsomepren.gallery.walkthrough

import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.data.PickerApi
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Loading old photos from Google Photos: the picker opens again after every round, photos picked
 * before are skipped, and a round with nothing new ends it. Played by a local Picker server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudSyncTest {
    @get:Rule
    val setup = AppSetupRule()

    private val server = MockWebServer()
    private val opened = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private var sessions = 0

    // What the user picks in each round: the second repeats one, the third brings nothing new.
    private val rounds = listOf(listOf("a", "b"), listOf("b", "c"), listOf("a", "c"))

    private val jpeg = ByteArrayOutputStream().also {
        Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
    }.toByteArray()

    @Before
    fun setUp() {
        File(setup.app.filesDir, "cloud").deleteRecursively()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!
                return when {
                    request.method == "POST" && path == "/sessions" -> {
                        sessions++
                        json(
                            JSONObject().put("id", "s$sessions").put("pickerUri", "https://photos.google.com/picker/s$sessions")
                                .put("pollingConfig", JSONObject().put("pollInterval", "0.01s").put("timeoutIn", "60s")),
                        )
                    }
                    request.method == "DELETE" -> {
                        deleted += path.substringAfterLast('/')
                        json(JSONObject())
                    }
                    path.startsWith("/sessions/") -> json(JSONObject().put("mediaItemsSet", true))
                    path.startsWith("/mediaItems") -> items(path)
                    path.startsWith("/img/") -> MockResponse().setBody(Buffer().write(jpeg))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        PickerApi.baseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() = server.shutdown()

    /** Each round comes in pages of one item, like Google's paging. */
    private fun items(path: String): MockResponse {
        val round = path.substringAfter("sessionId=s").substringBefore('&').toInt() - 1
        val page = path.substringAfter("pageToken=", "0").toInt()
        val ids = rounds.getOrElse(round) { emptyList() }
        val item = ids.getOrNull(page)?.let { id ->
            JSONObject().put("id", id).put("createTime", "2019-03-1${ids.size}T10:00:00Z")
                .put("mediaFile", JSONObject().put("baseUrl", server.url("/img/$id").toString()).put("mimeType", "image/jpeg").put("filename", "IMG_$id.jpg"))
        }
        val body = JSONObject().put("mediaItems", JSONArray().apply { item?.let(::put) })
        if (page + 1 < ids.size) body.put("nextPageToken", "${page + 1}")
        return json(body)
    }

    private fun json(body: JSONObject) = MockResponse().setBody(body.toString()).addHeader("Content-Type", "application/json")

    private fun viewModel() = GalleryViewModel(setup.app, SavedStateHandle()).apply {
        loadMedia = { Fixtures.photos }
        cloudToken = { "token" }
        openPicker = { opened += it }
    }

    private fun waitFor(vm: GalleryViewModel, ready: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!ready() && System.currentTimeMillis() < until) {
            idleMain()
            Thread.sleep(20)
        }
        idleMain()
        assertTrue("sync did not finish: ${vm.cloudSync.value}", ready())
    }

    @Test
    fun picksAgainUntilNothingNew() {
        onWifi(setup.app)
        val vm = viewModel()
        vm.startCloudSync("me@gmail.com")
        waitFor(vm) { vm.cloudSync.value?.done == true }

        val state = vm.cloudSync.value!!
        assertEquals(3, state.added)
        assertEquals(3, vm.cloud.count)
        assertEquals(listOf("s1", "s2", "s3"), deleted)
        assertEquals(3, opened.size)
        assertTrue(opened.all { it.endsWith("/autoclose") })

        waitFor(vm) { vm.media.value.orEmpty().count { it.cloud } == 3 }
        val cloudItems = vm.media.value!!.filter { it.cloud }
        assertTrue(cloudItems.all { it.relativePath.isEmpty() && !it.isCameraShot && it.id < 0 })
        assertTrue(cloudItems.all { File(it.uri.path!!).length() in 1 until jpeg.size.toLong() * 2 })
    }

    @Test
    fun keepsPreviewsAcrossRestarts() {
        onWifi(setup.app)
        val vm = viewModel()
        vm.startCloudSync("me@gmail.com")
        waitFor(vm) { vm.cloudSync.value?.done == true }

        val again = viewModel()
        assertEquals(3, again.cloud.count)
        assertNotNull(again.cloud.oldest())
    }

    @Test
    fun neverOnMobileData() {
        val vm = viewModel()
        vm.startCloudSync("me@gmail.com")
        waitFor(vm) { vm.cloudSync.value?.error != null }

        assertEquals(0, server.requestCount)
        assertTrue(opened.isEmpty())
        assertEquals(0, vm.cloud.count)
    }

    @Test
    fun hiddenWhenSwitchedOff() {
        onWifi(setup.app)
        val vm = viewModel()
        vm.startCloudSync("me@gmail.com")
        waitFor(vm) { vm.cloudSync.value?.done == true }

        vm.setShowCloud(false)
        waitFor(vm) { vm.media.value?.none { it.cloud } == true }
        assertFalse(vm.media.value!!.isEmpty())
    }
}

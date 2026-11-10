package com.justsomepren.gallery.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLDecoder

/** A small stand-in for photos.google.com that answers in the shape the real page does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WebPhotosTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val day = 1761753048000L

    /** Key, file name and time of each photo in the pretend library, newest first. */
    private var photos = listOf(Triple("A1", "PXL_1.jpg", day), Triple("B2", "PXL_2.mp4", day - 60_000), Triple("C3", "PXL_3.jpg", day - 120_000))
    private var signedIn = true
    private var broken = false
    private var hearts = setOf<String>()
    private var jar = "SID=abc"
    private val requests = mutableListOf<String>()

    private fun jpeg(): Buffer {
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2266AA.toInt()) }
        return Buffer().write(ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray())
    }

    private fun item(photo: Triple<String, String, Long>): JSONArray {
        val extras = JSONObject().put("163238866", JSONArray(listOf(photo.first in hearts)))
        if (photo.second.endsWith(".mp4")) extras.put("76647426", JSONArray(listOf(22932)))
        val thumb = JSONArray(listOf(server.url("/pw/${photo.first}").toString() + "=w1080-h1920", 1080, 1920))
        return JSONArray(listOf(photo.first, thumb, photo.third, "dedup", 3600000, photo.third, null, extras))
    }

    /** Two photos a page, so the test goes through more than one. */
    private fun page(next: String?): JSONArray {
        val from = next?.toInt() ?: 0
        val part = photos.drop(from).take(2)
        return JSONArray(listOf(JSONArray(part.map(::item)), if (from + 2 < photos.size) "${from + 2}" else null, "0"))
    }

    private fun names(keys: JSONArray): JSONArray {
        val wanted = (0 until keys.length()).map { keys.getJSONArray(it).getString(0) }
        val list = photos.filter { it.first in wanted }.map { JSONArray(listOf(it.first, JSONArray(listOf(null, null, "", it.second)))) }
        return JSONArray(listOf(JSONArray(listOf(null, JSONArray(list)))))
    }

    private fun wrapped(id: String, data: JSONArray) =
        ")]}'\n\n123\n" + JSONArray(listOf(JSONArray(listOf("wrb.fr", id, data.toString(), null, null, null, "generic")))) + "\n25\n[[\"e\",4,null,null,138]]\n"

    @Before
    fun setUp() {
        File(context.filesDir, "cloud").deleteRecursively()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += path.substringBefore('?') + (request.requestUrl?.queryParameter("rpcids")?.let { " $it" } ?: "")
                val cookie = request.getHeader("Cookie").orEmpty()
                if (!cookie.startsWith("SID=abc")) return MockResponse().setResponseCode(403)
                return when {
                    path == "/" -> MockResponse().setBody(
                        if (signedIn) """<script>window.WIZ_global_data = {"FdrFJe":"-123","SNlM0e":"TOKEN:1","cfb2h":"boq_1","eptZe":"\/_\/PhotosUi\/"};</script>"""
                        else "<html>Sign in</html>"
                    )
                    path.startsWith("/_/PhotosUi/data/batchexecute") -> {
                        val form = request.body.readUtf8().split('&').filter { '=' in it }
                            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                        if (form["at"] != "TOKEN:1") return MockResponse().setResponseCode(400)
                        val call = JSONArray(form["f.req"]).getJSONArray(0).getJSONArray(0)
                        val data = JSONArray(call.getString(1))
                        when (call.getString(0)) {
                            "VrseUb" -> {
                                val photo = photos.first { it.first == data.getString(0) }
                                MockResponse().setBody(wrapped("VrseUb", JSONArray(listOf(item(photo), server.url("/download/${photo.first}").toString()))))
                            }
                            "lcxiM" -> MockResponse().setBody(wrapped("lcxiM", page(if (data.isNull(0)) null else data.getString(0))))
                            else -> MockResponse().setBody(wrapped("EWgK9e", names(data.getJSONArray(0).getJSONArray(0).getJSONArray(0))))
                        }
                    }
                    path.startsWith("/signin") -> MockResponse().setResponseCode(302)
                        .addHeader("Set-Cookie", "PICTURES=1; Path=/; HttpOnly")
                        .addHeader("Location", request.requestUrl!!.queryParameter("continue")!!)
                    // Like Google: the address of the pictures first sends the visitor to sign in
                    path.startsWith("/pw/") && "PICTURES=1" !in cookie && !broken ->
                        MockResponse().setResponseCode(302).addHeader("Location", "/signin?continue=$path")
                    path.startsWith("/download/") -> MockResponse().setBody("the video as uploaded")
                    path.startsWith("/pw/") && (path.endsWith("=d") || path.endsWith("=w2560-h2560")) -> MockResponse().setBody("photo ${path.substringAfter('=')}")
                    path.startsWith("/pw/") && path.endsWith("=w300-h300") ->
                        if (broken) MockResponse().addHeader("Content-Type", "text/html").setBody("<html>Sign in</html>") else MockResponse().setBody(jpeg())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        WebPhotos.site = server.url("/").toString().trimEnd('/')
    }

    @After
    fun tearDown() = server.shutdown()

    private fun sync(onPhone: Set<Pair<String, Long>> = emptySet()) = runBlocking {
        val names = WebNames(File(context.filesDir, "cloud/web-names.tsv"))
        val session = WebSession({ jar }, "test") { _, cookie -> jar += "; " + cookie.substringBefore(';') }
        WebPhotos.sync(session, CloudLibrary(context), names, onPhone, onFavorites = Prefs.get(context)::googleFavorites)
    }

    @Test
    fun addsPreviewsOfTheWholeLibraryAndLeavesOutPhotosInThePhone() {
        val result = sync(onPhone = setOf("PXL_3.jpg" to (day - 120_000) / 60_000))

        assertEquals(WebPhotos.Progress(listed = 3, added = 2, done = true), result)
        val items = CloudLibrary(context).asMediaItems(emptyList()).sortedByDescending { it.date }
        assertEquals(listOf("PXL_1.jpg", "PXL_2.mp4"), items.map { it.name })
        assertEquals(listOf(day, day - 60_000), items.map { it.date })
        assertEquals(listOf(false, true), items.map { it.isVideo })
        assertEquals("https://photos.google.com/photo/A1", items[0].link)
        assertTrue(items.all { File(it.uri.path!!).length() > 0 })
    }

    @Test
    fun nextRunAddsPhotoDeletedFromThePhoneAndRemovesOneDeletedInGooglePhotos() {
        sync(onPhone = setOf("PXL_3.jpg" to (day - 120_000) / 60_000))
        requests.clear()
        photos = photos.drop(1)

        val result = sync()

        assertEquals(WebPhotos.Progress(listed = 2, added = 1, done = true), result)
        assertEquals(setOf("web:B2", "web:C3"), CloudLibrary(context).ids())
        // Names are remembered and only the missing preview is downloaded
        assertFalse(requests.any { "EWgK9e" in it })
        assertEquals(listOf("/pw/C3=w300-h300"), requests.filter { it.startsWith("/pw/") })
    }

    @Test
    fun keepsPreviewsFromOtherSources() {
        CloudLibrary(context).add(CloudPhoto("takeout:OLD.jpg:1", 1000, "OLD.jpg", "image/jpeg"), jpeg().readByteArray())

        sync()

        assertTrue(CloudLibrary(context).has("takeout:OLD.jpg:1"))
    }

    @Test
    fun savesNothingWhenGoogleSendsAPageInsteadOfThePicture() {
        broken = true

        val result = sync()

        assertEquals(3, result.failed)
        assertTrue(result.reason!!.contains("text/html"))
        assertEquals(0, CloudLibrary(context).count)
    }

    @Test
    fun repairsPreviewsSavedTooBig() {
        val library = CloudLibrary(context)
        val big = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).apply { setPixels(IntArray(6_000_000) { it * 7919 }, 0, 3000, 0, 0, 3000, 2000) }
        library.add(CloudPhoto("web:BIG", day, "BIG.jpg", "image/jpeg"), ByteArrayOutputStream().also { big.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray())
        library.add(CloudPhoto("web:PAGE", day, "PAGE.jpg", "image/jpeg"), ByteArray(200_000) { 'x'.code.toByte() })
        library.add(CloudPhoto("web:FINE", day, "FINE.jpg", "image/jpeg"), jpeg().readByteArray())

        assertTrue(library.repair())

        assertEquals(setOf("web:BIG", "web:FINE"), CloudLibrary(context).ids())
        val fixed = android.graphics.BitmapFactory.decodeFile(CloudLibrary(context).asMediaItems(emptyList()).first { it.name == "BIG.jpg" }.uri.path)
        assertEquals(600 to 400, fixed.width to fixed.height)
        assertFalse(CloudLibrary(context).repair())
    }

    @Test
    fun loadsThePhotoInScreenSizeAndAsUploaded() {
        val session = WebSession({ jar }, "test") { _, cookie -> jar += "; " + cookie.substringBefore(';') }
        val file = File(context.cacheDir, "full/photo")

        session.fetch("A1", video = false, original = false, file)
        assertEquals("photo w2560-h2560", file.readText())
        session.fetch("A1", video = false, original = true, file)
        assertEquals("photo d", file.readText())
        session.fetch("B2", video = true, original = true, file)
        assertEquals("the video as uploaded", file.readText())
    }

    @Test
    fun takesFavoritesFromGooglePhotosButKeepsHeartsChangedInTheGallery() {
        val prefs = Prefs.get(context)
        prefs.setCloudFavorites(prefs.cloudFavorites.value, false)
        prefs.googleFavorites(emptySet())
        hearts = setOf("A1", "B2")
        sync()
        assertEquals(setOf("PXL_1.jpg|$day", "PXL_2.mp4|${day - 60_000}"), prefs.cloudFavorites.value)
        assertEquals(listOf(true, true, false), CloudPhotos.withCloud(context, emptyList()).map { it.isFavorite })

        // A heart taken away and one given in the gallery stay; one taken away in Google Photos goes
        prefs.setCloudFavorites(listOf("PXL_1.jpg|$day"), false)
        prefs.setCloudFavorites(listOf("PXL_3.jpg|${day - 120_000}"), true)
        hearts = setOf("A1")
        sync()

        assertEquals(setOf("PXL_3.jpg|${day - 120_000}"), prefs.cloudFavorites.value)
    }

    @Test
    fun asksForLoginWhenGoogleDoesNotSeeTheUser() {
        signedIn = false

        assertThrows(NeedsLogin::class.java) { WebSession({ "SID=abc" }, "test") }
    }

    @Test
    fun readsTheShapeOfTheRealPage() {
        val real = JSONArray(
            """[[["AF1Qip", ["https://photos.fife.usercontent.google.com/pw/X", 1080, 1920, null, null, null, null, null, [null, null, 1], [8877406]],
               1761753048000, "sK5V", 3600000, 1761755803160, null, [[1], [2]], 2, null, null, null, null, false, 53246,
               {"76647426": [22932, null, 1080, 1920], "163238866": [false]}]], "NEXT", "100000000020"]"""
        )

        val page = WebPhotos.parsePage(real)

        assertEquals(listOf(WebPhotos.Item("AF1Qip", "https://photos.fife.usercontent.google.com/pw/X", 1761753048000, true)), page.items)
        assertEquals("NEXT", page.next)
        val names = JSONArray("""[[null, [["AF1Qip", [null, null, "", "file_164.mp4", null, null, 1761753048000]]]]]""")
        assertEquals(mapOf("AF1Qip" to "file_164.mp4"), WebPhotos.parseNames(names))
    }
}

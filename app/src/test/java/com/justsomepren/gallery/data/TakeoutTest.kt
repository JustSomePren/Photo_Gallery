package com.justsomepren.gallery.data

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TakeoutTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val folder = "Takeout/Google Fotky"

    private fun jpeg(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun date(title: String, time: String) = JSONObject()
        .put("title", title)
        .put("photoTakenTime", JSONObject().put("timestamp", "${Instant.parse(time).epochSecond}"))
        .toString().toByteArray()

    private fun zip(vararg files: Pair<String, ByteArray>): () -> java.io.InputStream {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                files.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        return { bytes.inputStream() }
    }

    @Before
    fun setUp() {
        File(context.filesDir, "cloud").deleteRecursively()
    }

    @Test
    fun importsEachPhotoOnceWithGooglesDateFromAnotherPart() {
        val sea = jpeg(0xFF2266AA.toInt())
        val parts = listOf(
            zip(
                "$folder/Photos from 2019/IMG_1.jpg" to sea,
                "$folder/Dovolená/IMG_1.jpg" to sea,
                "$folder/Photos from 2019/IMG_2(1).jpg" to jpeg(0xFF55AA22.toInt()),
                "$folder/Photos from 2019/IMG_3.jpg" to jpeg(0xFFAA2222.toInt()),
                "$folder/Photos from 2019/notes.txt" to "x".toByteArray(),
            ),
            zip(
                "$folder/Photos from 2019/IMG_1.jpg.supplemental-metadata.json" to date("IMG_1.jpg", "2019-07-01T08:00:00Z"),
                "$folder/Photos from 2019/IMG_2.jpg.supplemental-metadata(1).json" to date("IMG_2.jpg", "2019-07-02T09:00:00Z"),
                "$folder/Photos from 2019/IMG_3.jpg.json" to date("IMG_3.jpg", "2019-07-03T10:00:00Z"),
            ),
        )
        val stillInPhone = setOf("IMG_3.jpg" to Instant.parse("2019-07-03T10:00:00Z").toEpochMilli() / 60_000)
        val library = CloudLibrary(context)

        val result = Takeout.import(context, library, parts, stillInPhone)

        assertEquals(2, result.added)
        val items = CloudLibrary(context).asMediaItems(emptyList()).sortedBy { it.date }
        assertEquals(listOf("IMG_1.jpg", "IMG_2(1).jpg"), items.map { it.name })
        assertEquals(Instant.parse("2019-07-01T08:00:00Z").toEpochMilli(), items[0].date)
        assertEquals(true, items.all { it.inCalendar && File(it.uri.path!!).length() > 0 })

        // A second import of the same files adds nothing
        assertEquals(0, Takeout.import(context, CloudLibrary(context), parts, stillInPhone).added)
    }
}

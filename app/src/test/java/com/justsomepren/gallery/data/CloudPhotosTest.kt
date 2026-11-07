package com.justsomepren.gallery.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudPhotosTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun googleItem(id: String, name: String, time: String) = JSONObject()
        .put("id", id)
        .put("filename", name)
        .put("mimeType", "image/jpeg")
        .put("productUrl", "https://photos.google.com/lr/photo/$id")
        .put("mediaMetadata", JSONObject().put("creationTime", time).put("width", "4000").put("height", "3000"))

    private val inPhone = MediaItem(
        id = 1, uri = Uri.parse("content://media/external/images/media/1"), mime = "image/jpeg", isVideo = false,
        date = 0, dateTaken = 0, bucketId = 1, bucketName = "Camera", isFavorite = false, name = "PXL_1.jpg",
        width = 1, height = 1, size = 1, relativePath = "DCIM/Camera/",
    )

    @Before
    fun setUp() {
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        Prefs.reset()
        val dir = File(context.filesDir, "cloud").apply { mkdirs() }
        File(dir, "index.json").writeText(
            JSONArray(listOf(googleItem("a", "PXL_1.jpg", "2026-08-10T10:00:00Z"), googleItem("b", "PXL_2.jpg", "2026-08-11T12:30:00Z"))).toString(),
        )
        CloudPhotos.forget()
    }

    @Test
    fun onlyPhotosThatLeftThePhoneAreAdded() {
        val all = CloudPhotos.withCloud(context, listOf(inPhone))
        assertEquals(listOf("PXL_1.jpg", "PXL_2.jpg"), all.map { it.name })

        val cloud = all.last()
        assertTrue(cloud.isCloud && cloud.isCameraShot && cloud.id < 0)
        assertEquals(Instant.parse("2026-08-11T12:30:00Z").toEpochMilli(), cloud.date)
        assertEquals("https://photos.google.com/lr/photo/b", cloud.cloudUrl)
        assertEquals(cloud, CloudPhotos.items(context).find { it.id == cloud.id })
    }

    @Test
    fun switchedOffShowsOnlyThePhone() {
        Prefs.get(context).setShowCloud(false)
        assertEquals(listOf(inPhone), CloudPhotos.withCloud(context, listOf(inPhone)))
    }
}

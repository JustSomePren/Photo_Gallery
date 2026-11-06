package com.justsomepren.gallery.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: Prefs

    private fun item(id: Long, name: String = "IMG_$id.jpg", favorite: Boolean = false, bucket: Long = 1) =
        MediaItem(
            id = id,
            uri = Uri.parse("content://media/external/images/media/$id"),
            mime = "image/jpeg",
            isVideo = false,
            date = 1_000L * id,
            dateTaken = 1_000L * id,
            bucketId = bucket,
            bucketName = "Camera",
            isFavorite = favorite,
            name = name,
            width = 100,
            height = 100,
            size = 10,
            relativePath = "DCIM/Camera/",
        )

    @Before
    fun setUp() {
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = Prefs(context)
    }

    private fun album() = prefs.albums.value.single()
    private fun source() = album().let { Source.Album(it.id, it.name) }

    @Test
    fun albumKeepsPhotosAndSurvivesRestart() {
        prefs.createAlbum("Výlet", listOf(item(1), item(2)))
        prefs.addToAlbum(album().id, listOf(item(2), item(3)))

        val reloaded = Prefs(context).albums.value.single()
        assertEquals("Výlet", reloaded.name)
        assertEquals(listOf(1L, 2L, 3L), reloaded.refs.map { it.id })
    }

    @Test
    fun removeFromAlbum() {
        prefs.createAlbum("A", listOf(item(1), item(2)))
        prefs.removeFromAlbum(album().id, listOf(item(1)))
        assertEquals(listOf(2L), album().refs.map { it.id })
    }

    @Test
    fun albumFindsPhotoAfterIdChanged() {
        prefs.createAlbum("A", listOf(item(1)))
        // Same file with a new MediaStore id (new phone or reindex), and an unrelated photo that got the old id.
        val media = listOf(item(50, name = "IMG_1.jpg").copy(dateTaken = 1_000L), item(1, name = "OTHER.jpg"))

        assertEquals(listOf(50L), media.filterBy(source(), prefs.albums.value).map { it.id })
    }

    @Test
    fun photoWithoutCaptureDateMatchesBySize() {
        val screenshot = item(1).copy(dateTaken = 0, size = 4242)
        prefs.createAlbum("A", listOf(screenshot))
        val moved = screenshot.copy(id = 9, date = 999_999)

        assertEquals(listOf(9L), listOf(moved).filterBy(source(), prefs.albums.value).map { it.id })
    }

    @Test
    fun exportAndImportOnNewPhone() {
        prefs.createAlbum("A", listOf(item(1), item(2)))
        val json = prefs.exportAlbums()

        setUp()
        assertEquals(1, prefs.importAlbums(json))
        assertEquals(listOf(1L, 2L), album().refs.map { it.id })

        // Importing the same file again replaces, not duplicates.
        prefs.importAlbums(json)
        assertEquals(1, prefs.albums.value.size)
    }

    @Test
    fun filterBySources() {
        val media = listOf(item(1, favorite = true), item(2, bucket = 7), item(3))
        assertEquals(3, media.filterBy(Source.All, emptyList()).size)
        assertEquals(listOf(1L), media.filterBy(Source.Favorites, emptyList()).map { it.id })
        assertEquals(listOf(2L), media.filterBy(Source.Folder(7, "X"), emptyList()).map { it.id })
        assertEquals(emptyList<MediaItem>(), media.filterBy(Source.Album("missing", "X"), emptyList()))
    }

    @Test
    fun targetsAndSettingsPersist() {
        val target = UploadTarget("Společný – Disk", "com.google.android.apps.docs", "Upload")
        prefs.addTargets(listOf(target))
        prefs.setThreshold(55)
        prefs.setWarmup(false)

        val reloaded = Prefs(context)
        assertEquals(listOf(target), reloaded.targets.value)
        assertEquals(55, reloaded.threshold.value)
        assertEquals(false, reloaded.warmup.value)

        reloaded.deleteTarget(target)
        assertEquals(emptyList<UploadTarget>(), Prefs(context).targets.value)
    }
}

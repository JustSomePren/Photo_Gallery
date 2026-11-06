package com.justsomepren.gallery.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun wipe() {
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "sent.txt").delete()
    }

    private val item = QueueItem("IMG_1.jpg|1", Uri.parse("content://media/1").toString(), "IMG_1.jpg", "image/jpeg", "ja@gmail.com", "Moře")

    @Before
    fun setUp() = wipe()

    @Test
    fun everythingComesBackAndSentPhotosStaySent() {
        val prefs = Prefs(context)
        prefs.createAlbum("Rodina")
        prefs.saveEvent(Event(name = "Dovča", color = EVENT_COLORS[0], startDay = 1, endDay = 3))
        prefs.addAccount("ja@gmail.com")
        prefs.setThreshold(55)
        prefs.setAlbumId("ja@gmail.com", "Moře", "google-album-1")
        prefs.enqueue(listOf(item))
        prefs.markSent(listOf(item))
        val backup = prefs.exportAll()

        wipe()
        assertTrue(Prefs(context).restore(backup))

        val restored = Prefs(context)
        assertEquals(listOf("Rodina"), restored.albums.value.map { it.name })
        assertEquals(listOf("Dovča"), restored.events.value.map { it.name })
        assertEquals(listOf("ja@gmail.com"), restored.accounts.value)
        assertEquals(55, restored.threshold.value)
        assertEquals("google-album-1", restored.albumId("ja@gmail.com", "Moře"))
        assertTrue(restored.isSent(item))
        assertEquals(0, restored.enqueue(listOf(item)))
    }

    @Test
    fun olderAlbumFileStillLoads() {
        val old = Prefs(context).apply { createAlbum("Výlet") }.exportAlbums()
        wipe()
        val prefs = Prefs(context)
        assertFalse(prefs.restore(old))
        assertEquals(listOf("Výlet"), prefs.albums.value.map { it.name })
    }
}

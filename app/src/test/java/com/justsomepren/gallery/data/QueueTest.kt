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
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: Prefs

    private fun photoOn(date: LocalDate, id: Long): MediaItem {
        val millis = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return MediaItem(
            id = id,
            uri = Uri.parse("content://media/external/images/media/$id"),
            mime = "image/jpeg",
            isVideo = false,
            date = millis,
            dateTaken = millis,
            bucketId = 1,
            bucketName = "Camera",
            isFavorite = false,
            name = "IMG_$id.jpg",
            width = 1,
            height = 1,
            size = 1,
            relativePath = "DCIM/Camera/",
        )
    }

    @Before
    fun setUp() {
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "sent.txt").delete()
        prefs = Prefs(context)
        prefs.addAccount("me@gmail.com")
        prefs.addAccount("shared@gmail.com")
    }

    private val trip = Event(
        name = "Dovča Praha",
        color = EVENT_COLORS[0],
        startDay = LocalDate.of(2026, 11, 6).toEpochDay(),
        endDay = LocalDate.of(2026, 11, 30).toEpochDay(),
        autoSend = true,
        account = "shared@gmail.com",
        album = "Dovča Praha",
    )

    @Test
    fun autoSendQueuesOnlyPhotosFromTheEventDays() {
        prefs.addAccount("shared@gmail.com")
        prefs.saveEvent(trip)
        val media = listOf(
            photoOn(LocalDate.of(2026, 11, 5), 1),
            photoOn(LocalDate.of(2026, 11, 6), 2),
            photoOn(LocalDate.of(2026, 11, 30), 3),
            photoOn(LocalDate.of(2026, 12, 1), 4),
        )

        assertEquals(2, prefs.collectAuto(media))
        assertEquals(listOf("IMG_2.jpg", "IMG_3.jpg"), prefs.queue.value.map { it.name })
        assertEquals("Dovča Praha", prefs.queue.value.first().album)
        assertEquals("shared@gmail.com", prefs.queue.value.first().account)

        // Running again adds nothing twice.
        assertEquals(0, prefs.collectAuto(media))
    }

    @Test
    fun eventWithoutAutoSendQueuesNothing() {
        prefs.saveEvent(trip.copy(autoSend = false))
        assertEquals(0, prefs.collectAuto(listOf(photoOn(LocalDate.of(2026, 11, 10), 1))))
    }

    @Test
    fun sentPhotosAreNeverQueuedAgainEvenAfterRestart() {
        val item = QueueItem.of(photoOn(LocalDate.of(2026, 11, 10), 1), "me@gmail.com", null)
        prefs.enqueue(listOf(item))
        prefs.markSent(prefs.queue.value)
        assertEquals(0, prefs.queue.value.size)

        val restarted = Prefs(context)
        assertEquals(0, restarted.enqueue(listOf(item)))
        assertEquals(0, restarted.enqueue(listOf(item.copy(account = "unknown@gmail.com"))))
        // The same photo can still go to a different account.
        assertEquals(1, restarted.enqueue(listOf(item.copy(account = "shared@gmail.com"))))
    }

    @Test
    fun failedPhotosCanBeRetried() {
        val item = QueueItem.of(photoOn(LocalDate.of(2026, 11, 10), 1), "me@gmail.com", "")
        assertEquals(null, item.album)
        prefs.enqueue(listOf(item))
        prefs.markFailed(prefs.queue.value)
        assertEquals(1, prefs.failed.value.size)

        prefs.retryFailed()
        assertEquals(0, prefs.failed.value.size)
        assertEquals(1, prefs.queue.value.size)
    }

    @Test
    fun eventsAndQueueSurviveRestart() {
        prefs.saveEvent(trip)
        prefs.saveEvent(trip.copy(name = "Přejmenováno"))
        prefs.enqueue(listOf(QueueItem.of(photoOn(LocalDate.of(2026, 11, 10), 1), "me@gmail.com", "A")))

        val restarted = Prefs(context)
        assertEquals(listOf("Přejmenováno"), restarted.events.value.map { it.name })
        assertEquals(trip.endDay, restarted.events.value.single().endDay)
        assertEquals("A", restarted.queue.value.single().album)
    }

    @Test
    fun removingAccountDropsItsQueue() {
        prefs.addAccount("me@gmail.com")
        prefs.enqueue(listOf(QueueItem.of(photoOn(LocalDate.of(2026, 11, 10), 1), "me@gmail.com", null)))
        prefs.removeAccount("me@gmail.com")
        assertEquals(listOf("shared@gmail.com"), prefs.accounts.value)
        assertEquals(0, prefs.queue.value.size)
    }

    @Test
    fun periodSourceAndRanges() {
        val media = listOf(photoOn(LocalDate.of(2026, 11, 6), 1), photoOn(LocalDate.of(2026, 12, 6), 2))
        assertEquals(listOf(1L), media.filterBy(trip.source, emptyList()).map { it.id })
        assertEquals("6. 11. – 30. 11. 2026", formatRange(trip.startDay, trip.endDay))
    }
}

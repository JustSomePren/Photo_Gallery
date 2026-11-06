package com.justsomepren.gallery.walkthrough

import com.justsomepren.gallery.data.Prefs
import com.justsomepren.gallery.data.QueueItem
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import com.justsomepren.gallery.walkthrough.Fixtures.trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the user does in the Sending tab, followed by the next refresh (app reopened / worker run). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QueueWalkthroughTest {
    @get:Rule
    val setup = AppSetupRule { prefs ->
        prefs.addAccount("shared@gmail.com")
        prefs.saveEvent(trip)
    }

    private val prefs get() = Prefs.get(setup.app)
    private val inTrip get() = photos.filter { trip.covers(it.day()) && it.isCameraShot }

    private fun com.justsomepren.gallery.data.MediaItem.day() =
        java.time.Instant.ofEpochMilli(date).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()

    @Test
    fun removedAccountGetsNothingMore() {
        prefs.collectAuto(photos)
        assertTrue(prefs.queue.value.isNotEmpty())
        // Sending tab: delete the account. Its queue is dropped...
        prefs.removeAccount("shared@gmail.com")
        assertEquals(0, prefs.queue.value.size)
        // ...and the next refresh (or worker run) must not queue anything for it again.
        prefs.collectAuto(photos)
        assertEquals(0, prefs.queue.value.size)
        assertEquals(false, prefs.events.value.single().autoSend)
    }

    @Test
    fun clearedQueueStaysEmpty() {
        prefs.collectAuto(photos)
        prefs.clearQueue()
        prefs.collectAuto(photos)
        assertEquals(0, prefs.queue.value.size)
        // Chosen by hand again, a photo goes back into the queue.
        assertEquals(1, prefs.enqueue(listOf(QueueItem.of(inTrip.first(), "shared@gmail.com", null))))
    }

    @Test
    fun failedPhotosAreNotRequeued() {
        prefs.collectAuto(photos)
        val rejected = prefs.queue.value.take(3)
        prefs.markFailed(rejected)
        prefs.collectAuto(photos)
        val requeued = prefs.queue.value.count { q -> rejected.any { it.sentKey == q.sentKey } }
        assertEquals(0, requeued)
        assertEquals(3, prefs.failed.value.size)
        // "Zkusit znovu" puts them back.
        prefs.retryFailed()
        assertEquals(0, prefs.failed.value.size)
        assertEquals(inTrip.size, prefs.queue.value.size)
    }

    @Test
    fun onlyCameraPhotosAreSent() {
        prefs.collectAuto(photos)
        assertEquals(inTrip.size, prefs.queue.value.size)
        val screenshots = photos.filter { trip.covers(it.day()) && !it.isCameraShot }
        assertTrue(screenshots.isNotEmpty())
        assertTrue(prefs.queue.value.none { q -> screenshots.any { it.key == q.key } })
    }

    @Test
    fun sanityTripHasPhotos() {
        assertTrue(inTrip.isNotEmpty())
        assertTrue(prefs.enqueue(listOf(QueueItem.of(photos[80], "shared@gmail.com", null))) == 1)
    }
}

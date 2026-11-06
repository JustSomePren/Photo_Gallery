package com.justsomepren.gallery.data

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarLinkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefs: Prefs
    private val day = LocalDate.of(2026, 8, 10)

    private fun millis(time: LocalDateTime) = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun photo(time: LocalDateTime, id: Long, folder: String = "DCIM/Camera/") = MediaItem(
        id = id,
        uri = Uri.parse("content://media/external/images/media/$id"),
        mime = "image/jpeg",
        isVideo = false,
        date = millis(time),
        dateTaken = millis(time),
        bucketId = 1,
        bucketName = "Camera",
        isFavorite = false,
        name = "IMG_$id.jpg",
        width = 1,
        height = 1,
        size = 1,
        relativePath = folder,
    )

    private val trip = CalendarItem(
        id = 7,
        begin = 0,
        end = 0,
        allDay = true,
        firstDay = day.toEpochDay(),
        lastDay = day.plusDays(6).toEpochDay(),
        title = "Chorvatsko",
        color = EVENT_COLORS[1],
        vacation = true,
        album = "Moře",
        account = "ja@gmail.com",
        autoSend = true,
    )

    private val party = CalendarItem(
        id = 8,
        begin = millis(day.atTime(18, 0)),
        end = millis(day.atTime(22, 0)),
        allDay = false,
        firstDay = day.toEpochDay(),
        lastDay = day.toEpochDay(),
        title = "Oslava",
        color = EVENT_COLORS[2],
    )

    private val accounts = listOf("ja@gmail.com")

    @Before
    fun setUp() {
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "sent.txt").delete()
        prefs = Prefs(context)
    }

    @Test
    fun syncReplacesOnlyVacationsFromTheCalendar() {
        val own = Event(name = "Vlastní", color = 0, startDay = day.toEpochDay(), endDay = day.toEpochDay())
        prefs.saveEvent(own)
        prefs.syncLinked(day.toEpochDay() - 30, day.toEpochDay() + 30, listOf(trip.toEvent(accounts)))
        prefs.syncLinked(day.toEpochDay() - 30, day.toEpochDay() + 30, listOf(trip.copy(title = "Řecko").toEvent(accounts)))

        assertEquals(listOf("Vlastní", "Řecko"), prefs.events.value.map { it.name })
        // Survives a restart of the app
        val linked = Prefs(context).events.value.last()
        assertEquals(7L, linked.linkedId)
        assertEquals("Moře", linked.album)

        prefs.syncLinked(day.toEpochDay() - 30, day.toEpochDay() + 30, emptyList())
        assertEquals(listOf("Vlastní"), prefs.events.value.map { it.name })
    }

    @Test
    fun vacationSendsPhotosWithoutScreenshots() {
        prefs.syncLinked(day.toEpochDay() - 30, day.toEpochDay() + 30, listOf(trip.toEvent(accounts)))
        val media = listOf(
            photo(day.atTime(10, 0), 1),
            photo(day.atTime(11, 0), 2, folder = "Pictures/Screenshots/"),
            photo(day.plusDays(8).atTime(10, 0), 3),
        )

        assertEquals(1, prefs.collectAuto(media))
        assertEquals(QueueItem.of(media[0], "ja@gmail.com", "Moře"), prefs.queue.value.single())
    }

    @Test
    fun calendarCannotSendToAnAccountNotConnectedHere() {
        val event = trip.copy(account = "cizi@gmail.com").toEvent(accounts)
        assertEquals(false, event.autoSend)
    }

    @Test
    fun timedEventGetsPhotosFromShortlyBeforeToAfter() {
        val media = listOf(
            photo(day.atTime(17, 30), 1),
            photo(day.atTime(17, 50), 2),
            photo(day.atTime(20, 0), 3),
            photo(day.atTime(20, 5), 4, folder = "Pictures/Screenshots/"),
            photo(day.atTime(22, 20), 5),
            photo(day.atTime(23, 0), 6),
        )
        assertEquals(listOf(2L, 3L, 5L), media.filterBy(party.source, emptyList()).map { it.id })
        assertEquals(setOf(day.toEpochDay()), pairedDays(listOf(party, trip), media.groupBy { it.day }))
    }

    @Test
    fun placeIgnoresOnePhotoFromElsewhere() {
        val prague = doubleArrayOf(50.08, 14.42)
        val place = medianPlace(listOf(prague, doubleArrayOf(50.09, 14.43), doubleArrayOf(48.2, 16.37), prague))
        assertArrayEquals(prague, place, 0.02)
        assertNull(medianPlace(emptyList()))
    }
}

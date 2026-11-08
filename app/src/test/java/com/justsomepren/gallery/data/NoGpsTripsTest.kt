package com.justsomepren.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.OffsetDateTime

/** Trips for photos taken with location off: Timeline export, time zones, busy days. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoGpsTripsTest {
    private fun millis(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    @Test
    fun readsPhoneTimelineExport() {
        val json = """
            {"semanticSegments": [
              {"startTime": "2025-10-03T08:00:00.000+08:00", "endTime": "2025-10-03T12:00:00.000+08:00",
               "visit": {"topCandidate": {"placeLocation": {"latLng": "25.0330°, 121.5654°"}}}},
              {"startTime": "2025-10-03T12:00:00.000+08:00", "endTime": "2025-10-03T14:00:00.000+08:00",
               "timelinePath": [{"point": "24.1477°, 120.6736°", "time": "2025-10-03T13:00:00.000+08:00"}]}
            ],
            "rawSignals": [], "userLocationProfile": {"frequentPlaces": []}}
        """
        val timeline = Timeline.read(json.reader())
        assertEquals(2, timeline.size)
        // Inside the stay at Taipei 101.
        assertEquals(25.033, timeline.placeAt(millis("2025-10-03T10:30:00+08:00"))!!.first, 0.001)
        // Near the path point an hour later.
        assertEquals(24.1477, timeline.placeAt(millis("2025-10-03T13:40:00+08:00"))!!.first, 0.001)
        // A day later nothing is close enough.
        assertNull(timeline.placeAt(millis("2025-10-04T20:00:00+08:00")))
    }

    @Test
    fun readsOlderTakeoutAndGeoFormats() {
        val takeout = """{"locations": [{"latitudeE7": 501000000, "longitudeE7": 144200000, "timestamp": "2020-05-01T10:00:00Z"}]}"""
        assertEquals(50.1, Timeline.read(takeout.reader()).placeAt(millis("2020-05-01T10:30:00Z"))!!.first, 0.001)

        val ios = """[{"startTime": "2021-07-01T09:00:00Z", "endTime": "2021-07-01T11:00:00Z",
                       "visit": {"topCandidate": {"placeLocation": "geo:45.4642,9.1900"}}}]"""
        assertEquals(9.19, Timeline.read(ios.reader()).placeAt(millis("2021-07-01T10:00:00Z"))!!.second, 0.001)
    }

    @Test
    fun timeZoneTripAbroadButNotSummerTimeAtHome() {
        val day = { d: LocalDate -> d.toEpochDay() }
        val zones = mutableMapOf<Long, Int>()
        // Home: winter time +1, summer time +2.
        (0L until 60).forEach { zones[day(LocalDate.of(2025, 2, 1).plusDays(it))] = 60 }
        (0L until 60).forEach { zones[day(LocalDate.of(2025, 7, 1).plusDays(it))] = 120 }
        // Taiwan +8 for six days.
        (0L until 6).forEach { zones[day(LocalDate.of(2025, 10, 3).plusDays(it))] = 480 }

        val trips = TripFinder.byTimeZone(zones, skip = emptySet())
        assertEquals(listOf("Výlet UTC+8 – říjen 2025"), trips.map { it.name })
        assertEquals(day(LocalDate.of(2025, 10, 8)), trips.single().endDay)
    }

    @Test
    fun busyDaysInARowLookLikeATrip() {
        val day = { d: LocalDate -> d.toEpochDay() }
        val perDay = mutableMapOf<Long, Int>()
        (0L until 40).forEach { perDay[day(LocalDate.of(2025, 9, 1).plusDays(it))] = 3 }
        (0L until 4).forEach { perDay[day(LocalDate.of(2025, 10, 15).plusDays(it))] = 40 }
        // One busy day alone (a party) is not a trip.
        perDay[day(LocalDate.of(2025, 10, 25))] = 60

        val trips = TripFinder.byBursts(perDay, skip = emptySet())
        assertEquals(1, trips.size)
        assertTrue(trips.single().name, trips.single().name.startsWith("Výlet 15. 10."))
        assertEquals(160, trips.single().photos)
    }

    @Test
    fun busyMeansBusierThanUsualAtThatTime() {
        val day = { d: LocalDate -> d.toEpochDay() }
        val perDay = mutableMapOf<Long, Int>()
        // Years ago: one photo a day, a holiday with 12 a day still stands out.
        (0L until 60).forEach { perDay[day(LocalDate.of(2019, 3, 1).plusDays(it))] = 1 }
        (0L until 5).forEach { perDay[day(LocalDate.of(2019, 4, 10).plusDays(it))] = 12 }
        // Nowadays 20 photos a day is normal and must not look like a trip.
        (0L until 90).forEach { perDay[day(LocalDate.of(2025, 1, 1).plusDays(it))] = 20 }

        val trips = TripFinder.byBursts(perDay, skip = emptySet())
        assertEquals(listOf(day(LocalDate.of(2019, 4, 10))), trips.map { it.startDay })
        assertEquals("12× víc fotek než obvykle", trips.single().note)
    }

    /** Someone who takes a couple of photos a month: a week with many photos is still a trip. */
    @Test
    fun rarePhotographerWeekOfPhotos() {
        val day = { d: LocalDate -> d.toEpochDay() }
        val perDay = mutableMapOf<Long, Int>()
        (0L until 36).forEach { perDay[day(LocalDate.of(2024, 1, 3).plusDays(it * 20))] = 2 }
        // Holiday week: some days without photos, one calmer day.
        listOf(15, 0, 22, 30, 4, 0, 25).forEachIndexed { i, n ->
            if (n > 0) perDay[day(LocalDate.of(2025, 7, 12).plusDays(i.toLong()))] = n
        }

        val trip = TripFinder.byBursts(perDay, skip = emptySet()).single()
        assertEquals(day(LocalDate.of(2025, 7, 12)), trip.startDay)
        assertEquals(day(LocalDate.of(2025, 7, 18)), trip.endDay)
        assertEquals(96, trip.photos)
    }

    @Test
    fun twoBusyDaysWithTwoEmptyDaysBetweenAreNoTrip() {
        val day = { d: LocalDate -> d.toEpochDay() }
        val perDay = mutableMapOf<Long, Int>()
        (0L until 30).forEach { perDay[day(LocalDate.of(2025, 9, 1).plusDays(it * 2))] = 3 }
        perDay[day(LocalDate.of(2025, 11, 10))] = 50
        perDay[day(LocalDate.of(2025, 11, 13))] = 45

        assertEquals(emptyList<TripProposal>(), TripFinder.byBursts(perDay, skip = emptySet()))
    }

    @Test
    fun calmerDayInsideHolidayKeepsItTogether() {
        val day = { d: LocalDate -> d.toEpochDay() }
        val perDay = mutableMapOf<Long, Int>()
        (0L until 60).forEach { perDay[day(LocalDate.of(2025, 9, 1).plusDays(it))] = 4 }
        listOf(30, 35, 8, 40).forEachIndexed { i, n -> perDay[day(LocalDate.of(2025, 10, 10).plusDays(i.toLong()))] = n }

        val trip = TripFinder.byBursts(perDay, skip = emptySet()).single()
        assertEquals(day(LocalDate.of(2025, 10, 10)), trip.startDay)
        assertEquals(day(LocalDate.of(2025, 10, 13)), trip.endDay)
    }

    @Test
    fun tripByPlaceTakesInBusyTravelDays() {
        val day = { d: LocalDate -> d.toEpochDay() }
        val perDay = mutableMapOf<Long, Int>()
        (0L until 60).forEach { perDay[day(LocalDate.of(2025, 9, 1).plusDays(it))] = 3 }
        // Flight day without location (18 photos), then 5 days in Taiwan with location, flight back.
        (0L until 7).forEach { perDay[day(LocalDate.of(2025, 10, 2).plusDays(it))] = 18 }
        val found = TripProposal(name = "Tchaj-wan – říjen 2025", startDay = day(LocalDate.of(2025, 10, 3)),
            endDay = day(LocalDate.of(2025, 10, 7)), photos = 90, note = "podle polohy")
        val known = (3L..7).map { day(LocalDate.of(2025, 10, 1).plusDays(it - 1)) }.toSet()

        val trip = TripFinder.extendByBusyDays(found, perDay, TripFinder.busyness(perDay), known)
        assertEquals(day(LocalDate.of(2025, 10, 2)), trip.startDay)
        assertEquals(day(LocalDate.of(2025, 10, 8)), trip.endDay)
        assertTrue(trip.note, trip.note.startsWith("podle polohy · "))
    }

    @Test
    fun offsetText() {
        assertEquals(480, parseOffset("+08:00"))
        assertEquals(-330, parseOffset("-05:30"))
        assertNull(parseOffset(null))
    }
}

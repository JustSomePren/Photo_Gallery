package com.justsomepren.gallery.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TripFinderTest {
    private val places = Places.get(ApplicationProvider.getApplicationContext<Context>())
    private val brno = 49.195 to 16.608
    private val taipei = 25.05 to 121.53
    private val prague = 50.08 to 14.42

    private fun spots(from: LocalDate, days: Int, at: Pair<Double, Double>, perDay: Int = 5) =
        (0 until days).flatMap { d ->
            List(perDay) { i -> Spot(from.plusDays(d.toLong()).toEpochDay(), at.first + i * 0.001, at.second) }
        }

    @Test
    fun nearestCityAndCountryNames() {
        assertEquals("Taipei", places.nearest(taipei.first, taipei.second)?.name)
        assertEquals("Brno", places.nearest(brno.first, brno.second)?.name)
        assertEquals("Tchaj-wan", countryName("TW"))
    }

    @Test
    fun findsTripsAbroadAndAtHomeButNotOrdinaryDays() {
        val all = spots(LocalDate.of(2025, 9, 1), 30, brno) +
            spots(LocalDate.of(2025, 10, 3), 8, taipei) +
            spots(LocalDate.of(2025, 10, 11), 20, brno) +
            // A day in Prague with plenty of photos counts too…
            spots(LocalDate.of(2025, 11, 2), 1, prague, perDay = 12) +
            spots(LocalDate.of(2025, 11, 3), 10, brno) +
            // …a quick stop with two photos does not.
            spots(LocalDate.of(2025, 11, 20), 1, prague, perDay = 2)

        val trips = TripFinder.find(all, places)

        assertEquals(listOf("Tchaj-wan – říjen 2025", "Prague – listopad 2025"), trips.map { it.name })
        assertEquals(LocalDate.of(2025, 10, 3).toEpochDay(), trips[0].startDay)
        assertEquals(LocalDate.of(2025, 10, 10).toEpochDay(), trips[0].endDay)
    }

    @Test
    fun dayWithoutPhotosInsideTripKeepsItTogether() {
        val all = spots(LocalDate.of(2025, 9, 1), 30, brno) +
            spots(LocalDate.of(2025, 10, 3), 2, taipei) +
            // 5. 10. no photos at all
            spots(LocalDate.of(2025, 10, 6), 2, taipei)
        val trips = TripFinder.find(all, places)
        assertEquals(1, trips.size)
        assertTrue(trips[0].endDay - trips[0].startDay == 4L)
    }

    @Test
    fun noPhotosWithPlaceMeansNoTrips() {
        assertTrue(TripFinder.find(emptyList(), places).isEmpty())
    }
}

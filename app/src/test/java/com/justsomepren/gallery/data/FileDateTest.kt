package com.justsomepren.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Files without a capture date must not all land on the day the phone was set up. */
class FileDateTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test
    fun dateInFileName() {
        assertEquals(at(2023, 5, 14), dateFromName("IMG-20230514-WA0003.jpg"))
        assertEquals(at(2023, 5, 14), dateFromName("VID-20230514-WA0001.mp4"))
        assertEquals(at(2024, 1, 2, 10, 11, 12), dateFromName("Screenshot_20240102-101112.png"))
        assertEquals(at(2024, 1, 2, 10, 11, 12), dateFromName("Screenshot_2024-01-02-10-11-12-345_com.app.png"))
        assertEquals(at(2022, 8, 9, 7, 8, 9), dateFromName("PXL_20220809_070809123.jpg"))
        assertEquals(at(2021, 3, 4, 5, 6, 7), dateFromName("signal-2021-03-04-05-06-07.jpg"))
    }

    @Test
    fun noDateInFileName() {
        assertNull(dateFromName("photo.jpg"))
        assertNull(dateFromName("IMG_1234.JPG"))
        // Looks like a date but is in the future.
        assertNull(dateFromName("IMG-20991231-WA0001.jpg"))
        assertNull(dateFromName("20231399.jpg"))
    }

    @Test
    fun fallbackPrefersNameThenOlderFileDate() {
        val setUpDay = at(2026, 10, 11) / 1000
        assertEquals(at(2019, 6, 1), fallbackDate("IMG-20190601-WA0001.jpg", setUpDay, setUpDay))
        // No date in the name: the older of modified and added.
        assertEquals(at(2018, 2, 3) / 1000 * 1000, fallbackDate("meme.jpg", setUpDay, at(2018, 2, 3) / 1000))
        assertEquals(setUpDay * 1000, fallbackDate("meme.jpg", setUpDay, 0))
    }

    @Test
    fun whatsAppDateSetOnArrivalLosesToTheNameButCameraKeepsItsOwn() {
        val arrival = at(2026, 10, 11)
        assertEquals(at(2019, 6, 1), bestDate(arrival, "IMG-20190601-WA0001.jpg", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", 0, 0))
        // Same day within hours: the stored time is more precise.
        val evening = at(2019, 6, 1, 20)
        assertEquals(evening, bestDate(evening, "IMG-20190601-WA0001.jpg", "Pictures/WhatsApp/", 0, 0))
        // Camera photos keep their capture date whatever the name says.
        assertEquals(arrival, bestDate(arrival, "PXL_20190601_101010.jpg", "DCIM/Camera/", 0, 0))
    }
}

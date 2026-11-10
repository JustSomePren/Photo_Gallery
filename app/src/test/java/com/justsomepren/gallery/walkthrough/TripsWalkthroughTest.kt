package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.Prefs
import com.justsomepren.gallery.data.day
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Settings → Najít výlety → confirm: trips become events and albums; nothing happens before the button. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class TripsWalkthroughTest {
    private val setup = AppSetupRule()
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]
    private val prefs get() = Prefs.get(setup.app)
    private val camera get() = photos.filter { it.isCameraShot }

    /** Places as if read from the photos: the newest 9 days in Taipei, the rest at home in Brno. */
    private fun writePlaces() {
        val newest = camera.maxOf { it.day }
        val lines = camera.joinToString("") { item: MediaItem ->
            val (lat, lon) = if (item.day > newest - 9) 25.05 to 121.53 else 49.195 to 16.608
            "${item.key}\t$lat\t$lon\t-\n"
        }
        File(setup.app.filesDir, "locations.tsv").writeText(lines)
    }

    /** Location was off: no places in the photos, but a Timeline export knows where the phone was. */
    @Test
    fun photosWithoutGpsGetPlacesFromTimeline() {
        val newest = camera.maxOf { it.day }
        File(setup.app.filesDir, "locations.tsv").writeText(camera.joinToString("") { "${it.key}\t-\t-\t-\n" })
        val points = camera.joinToString(",") { item ->
            val place = if (item.day > newest - 9) "25.05°, 121.53°" else "49.195°, 16.608°"
            val time = java.time.Instant.ofEpochMilli(item.date).toString()
            "{\"point\": \"$place\", \"time\": \"$time\"}"
        }
        val file = File(setup.app.cacheDir, "Timeline.json").apply {
            writeText("{\"semanticSegments\": [{\"timelinePath\": [$points]}]}")
        }
        start()

        val filled = kotlinx.coroutines.runBlocking { vm.importTimeline(android.net.Uri.fromFile(file)) }
        assertEquals(camera.size, filled)
        compose.waitUntil(30_000) { idleMain(); prefs.tripSearch.value == null && prefs.trips.value.isNotEmpty() }
        assertTrue(prefs.trips.value.single().name.startsWith("Tchaj-wan"))
    }

    private fun start() {
        vm.loadMedia = { photos }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value?.isNotEmpty() == true }
        compose.waitForIdle()
    }

    @Test
    fun findConfirmAndCreate() {
        writePlaces()
        start()
        assertTrue("nothing runs before the button", prefs.trips.value.isEmpty())

        compose.onNodeWithText("Nastavení").performClick()
        compose.onNodeWithText("Najít výlety").performScrollTo().performClick()
        compose.waitUntil(30_000) { idleMain(); prefs.tripSearch.value == null && prefs.trips.value.isNotEmpty() }
        compose.waitForIdle()

        val trip = prefs.trips.value.single()
        assertTrue(trip.name, trip.name.startsWith("Tchaj-wan"))
        // Evenly spread test photos: no "more photos than usual" claim.
        assertEquals("podle polohy", trip.note)

        compose.onNodeWithText("Návrhy výletů: 1").performScrollTo().performClick()
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/walkthrough/trips_dialog.png")
        compose.onNodeWithText("Vytvořit (1)").performClick()
        compose.waitForIdle()

        val event = prefs.events.value.single()
        assertEquals(trip.name, event.name)
        assertEquals(false, event.autoSend)
        val album = prefs.albums.value.single()
        assertEquals(trip.photos, album.refs.size)
        assertTrue(prefs.trips.value.isEmpty())

        // A second search does not propose the same trip again.
        vm.findTrips()
        compose.waitUntil(30_000) { idleMain(); prefs.tripSearch.value == null }
        assertTrue(prefs.trips.value.isEmpty())
    }

    /** A holiday week without any place: found by the many photos, named by the AI, renamed by hand. */
    @Test
    fun busyWeekWithoutPlaceGetsAiNameAndCanBeRenamed() {
        val today = Fixtures.today
        val normal = (0L until 120L step 4).map { Fixtures.photo(it, today.minusDays(40 + it)) }
        val holiday = (0L until 60L).map { Fixtures.photo(1000 + it, today.minusDays(10 + it / 10)) }
        val all = (normal + holiday).sortedByDescending { it.date }
        File(setup.app.filesDir, "locations.tsv").writeText(all.joinToString("") { "${it.key}\t-\t-\t-\n" })
        val asked = mutableListOf<Int>()
        vm.nameTrip = { photos -> asked += photos.size; "Hory na sněhu" }
        vm.loadMedia = { all }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value == all }

        vm.findTrips()
        compose.waitUntil(30_000) { idleMain(); prefs.tripSearch.value == null }
        val trip = prefs.trips.value.single()
        assertEquals("Hory na sněhu", trip.name)
        assertEquals(listOf(holiday.count { it.isCameraShot }), asked)
        assertTrue(trip.note, trip.note.endsWith("název navrhla AI v telefonu"))

        compose.onNodeWithText("Nastavení").performClick()
        compose.onNodeWithText("Návrhy výletů: 1").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Přejmenovat").assertExists()
        captureScreenRoboImage("build/outputs/walkthrough/trips_dialog_ai.png")
        // The text field itself cannot be typed into here (its blinking cursor never lets the test rest).
        prefs.renameTrip(trip.id, "Krkonoše")
        assertEquals("Krkonoše", prefs.trips.value.single().name)
    }

    /** Without the AI the trip keeps a name with its dates, and an empty search says so. */
    @Test
    fun withoutAiDatedNameAndEmptySearchSaysSo() {
        vm.nameTrip = { null }
        File(setup.app.filesDir, "locations.tsv").writeText(camera.joinToString("") { "${it.key}\t-\t-\t-\n" })
        start()
        vm.findTrips()
        compose.waitUntil(30_000) { idleMain(); prefs.tripSearch.value == null }
        assertTrue(prefs.trips.value.isEmpty())
        assertTrue(vm.tripResult.value.orEmpty().startsWith("Žádné nové výlety"))
    }
}

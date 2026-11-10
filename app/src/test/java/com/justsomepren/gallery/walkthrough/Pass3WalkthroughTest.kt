package com.justsomepren.gallery.walkthrough

import android.content.Intent
import android.os.BatteryManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.EVENT_COLORS
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.data.day
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import com.justsomepren.gallery.walkthrough.Fixtures.today
import com.justsomepren.gallery.walkthrough.Fixtures.trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 3: events editing, overlapping events, long names, saver mode, empty states, rotation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass3WalkthroughTest {
    private val setup = AppSetupRule { prefs ->
        prefs.addAccount("shared@gmail.com")
        prefs.addAccount("me@gmail.com")
    }
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]
    private val prefs get() = vm.prefs

    private fun start(media: List<MediaItem> = photos) {
        vm.loadMedia = { media }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value?.size == media.size }
        compose.waitForIdle()
    }

    private fun settle() {
        repeat(6) { compose.mainClock.advanceTimeBy(200); idleMain() }
    }

    private fun photoNode(i: Int) = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))[i]
    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    private fun tripPhotos() = photos.filter { it.isCameraShot && trip.covers(it.day) }

    @Test
    fun changingEventAlbumMovesQueuedPhotosToNewAlbum() {
        prefs.saveEvent(trip)
        start()
        assertTrue(prefs.queue.value.isNotEmpty())
        assertTrue(prefs.queue.value.all { it.album == "Dovca Praha" })
        // Same call the editor's "Uložit" makes after the user types a new album name.
        vm.saveEvent(prefs.events.value.single().copy(album = "Praha 2026"))
        assertEquals(tripPhotos().size, prefs.queue.value.size)
        assertTrue(prefs.queue.value.all { it.album == "Praha 2026" })
    }

    @Test
    fun deletingOverlappingEventKeepsOtherEventsPhotosQueued() {
        prefs.saveEvent(trip)
        // A second event on the same days, sending to the same account.
        prefs.saveEvent(trip.copy(id = "trip2", name = "Praha s rodinou", color = EVENT_COLORS[3]))
        start()
        val expected = tripPhotos().size
        assertEquals(expected, prefs.queue.value.size)
        vm.deleteEvent(prefs.events.value.first { it.id == trip.id })
        // "trip2" still sends these days, so they must stay queued.
        assertEquals(expected, prefs.queue.value.size)
    }

    @Test
    fun manyOverlappingLongEventsAcrossYearsLookOk() {
        val long = "Velmi dlouhy nazev udalosti na hory s celou rodinou a psem"
        prefs.saveEvent(trip.copy(name = long, autoSend = true))
        prefs.saveEvent(Event(id = "e2", name = "Druha", color = EVENT_COLORS[0], startDay = today.minusDays(6).toEpochDay(), endDay = today.toEpochDay()))
        prefs.saveEvent(Event(id = "e3", name = "Treti", color = EVENT_COLORS[3], startDay = today.minusDays(2).toEpochDay(), endDay = today.toEpochDay()))
        prefs.saveEvent(Event(id = "years", name = "Rok v Brne", color = EVENT_COLORS[6], startDay = today.minusYears(1).toEpochDay(), endDay = today.plusMonths(2).toEpochDay()))
        start()
        shot("p3_01_grid_overlapping_long")
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.4f) }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(300)
        shot("p3_02_grid_scrolled_pill")
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithContentDescription("Kalendář").performClick(); compose.waitForIdle()
        shot("p3_03_calendar_overlapping")
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        shot("p3_04_sending_many_events")
    }

    @Test
    fun emptyStatesNoPhotosNoAccountsNoEvents() {
        prefs.removeAccount("shared@gmail.com")
        prefs.removeAccount("me@gmail.com")
        start(emptyList())
        shot("p3_05_empty_grid")
        compose.onNodeWithText("Žádné fotky").assertIsDisplayed()
        compose.onNodeWithContentDescription("Kalendář").performClick(); compose.waitForIdle()
        shot("p3_06_empty_calendar")
        compose.onNodeWithText("Alba").performClick(); compose.waitForIdle()
        shot("p3_07_empty_albums")
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        shot("p3_08_empty_sending")
        compose.onNodeWithText("Vše odesláno").assertIsDisplayed()
    }

    @Test
    fun saverModeLooksDarkAndUsesFiveColumns() {
        setup.app.sendStickyBroadcast(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 20)
                .putExtra(BatteryManager.EXTRA_SCALE, 100)
                .putExtra(BatteryManager.EXTRA_PLUGGED, 0),
        )
        prefs.saveEvent(trip)
        start()
        compose.onNodeWithContentDescription("Úsporný režim").assertIsDisplayed()
        shot("p3_09_saver_grid")
        compose.onNodeWithText("Nastavení").performClick(); compose.waitForIdle()
        shot("p3_10_saver_settings")
        compose.onNodeWithText("Úsporný režim je zapnutý").performScrollTo().assertIsDisplayed()
        vm.stack.add(Screen.Viewer(Source.All, photos[3].id))
        compose.waitForIdle()
        shot("p3_11_saver_viewer")
    }

    @Test
    fun eventEditorDatePickerAndLongNames() {
        prefs.saveEvent(trip.copy(name = "Velmi dlouhy nazev udalosti na hory s celou rodinou a psem"))
        start()
        compose.mainClock.autoAdvance = false
        vm.editingEvent = prefs.events.value.single()
        settle()
        shot("p3_12_editor_long_name")
        compose.onAllNodesWithText(com.justsomepren.gallery.data.formatRange(trip.startDay, trip.endDay)).filterToOne(androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog())).performClick()
        settle()
        compose.onNodeWithText("OK").assertExists()
        shot("p3_13_date_range_picker")
    }

    @Test
    fun longEventNameKeepsGridEventRowCompact() {
        val long = "Velmi dlouhy nazev udalosti na hory s celou rodinou a psem"
        prefs.saveEvent(trip.copy(name = long))
        start()
        shot("p3_15_grid_long_event_row")
        val range = com.justsomepren.gallery.data.formatRange(trip.startDay, trip.endDay)
        val row = compose.onNode(
            androidx.compose.ui.test.hasText(range, substring = true) and androidx.compose.ui.test.hasText(long, substring = true) and
                androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog()).not(),
        ).fetchSemanticsNode()
        val heightDp = row.size.height / compose.density.density
        assertTrue("event row is ${heightDp}dp tall", heightDp < 80f)
    }

    @Test
    fun monthHeaderSelectionAcrossBoundaryAndUnselect() {
        start()
        val firstMonth = photos.filter { it.month == photos[0].month }
        compose.onAllNodesWithText(com.justsomepren.gallery.data.monthTitle(photos[0].month))[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("${firstMonth.size}").assertIsDisplayed()
        compose.onAllNodesWithText(com.justsomepren.gallery.data.monthTitle(photos[0].month))[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Zrušit výběr").assertDoesNotExist()
        photoNode(0).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Vybrat vše").performClick(); compose.waitForIdle()
        compose.onNodeWithText("${photos.size}").assertIsDisplayed()
        shot("p3_14_select_all")
    }

    private val MediaItem.month get() = java.time.YearMonth.from(day.let { java.time.LocalDate.ofEpochDay(it) })
}

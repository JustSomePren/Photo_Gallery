package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.data.day
import com.justsomepren.gallery.walkthrough.Fixtures.photos
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

/** Bug-hunt pass 10: refusals after an event is switched off and on, calendar, viewer, editor flows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass10WalkthroughTest {
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

    /**
     * Google refused 2 trip photos; the Sending tab says "2 se nepodařilo odeslat" with "Zkusit znovu".
     * The user switches the trip's auto-send off and on again (e.g. to check the setting). The 2 photos are
     * neither queued nor listed as failed any more: they are never sent and nothing tells the user.
     */
    @Test
    fun turningEventOffAndOnKeepsRefusedPhotosAmongFailed() {
        prefs.saveEvent(trip)
        start()
        val refused = prefs.queue.value.take(2)
        prefs.markFailed(refused)
        assertEquals(2, prefs.failed.value.size)

        val event = prefs.events.value.single()
        vm.saveEvent(event.copy(autoSend = false, account = null, album = null))
        vm.saveEvent(event)
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        shot("p10_01_sending_after_off_on")
        val keys = refused.map { it.sentKey }.toSet()
        val visible = (prefs.failed.value + prefs.queue.value).map { it.sentKey }.toSet()
        assertTrue("refused photos vanished: neither failed nor queued", visible.containsAll(keys))
    }

    /** Same through deleting the trip and creating it again over the same days. */
    @Test
    fun recreatedEventShowsRefusedPhotosAsFailed() {
        prefs.saveEvent(trip)
        start()
        val refused = prefs.queue.value.take(2)
        prefs.markFailed(refused)
        vm.deleteEvent(prefs.events.value.single())
        assertTrue(prefs.failed.value.isEmpty())
        vm.saveEvent(trip.copy(id = "trip2"))
        val keys = refused.map { it.sentKey }.toSet()
        val visible = (prefs.failed.value + prefs.queue.value).map { it.sentKey }.toSet()
        assertTrue("refused photos vanished: neither failed nor queued", visible.containsAll(keys))
    }

    /** Long press on a day in the calendar opens a new event for that day; saving shows its bar and chip. */
    @Test
    fun calendarLongPressCreatesEventOnThatDay() {
        start()
        prefs.setCalendarView(true)
        compose.waitForIdle()
        val day = Fixtures.today.minusDays(2)
        val label = compose.onAllNodes(androidx.compose.ui.test.hasText("${day.dayOfMonth}"))
        label[0].performTouchInput { longClick() }
        compose.mainClock.autoAdvance = false
        settle()
        shot("p10_02_calendar_new_event")
        val editing = vm.editingEvent!!
        assertTrue(editing.startDay == editing.endDay)
        compose.onNodeWithText("Uložit").performClick()
        settle()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        shot("p10_03_calendar_after_save")
        assertEquals(1, prefs.events.value.size)
    }

    /** Tapping the event row in the grid opens the editor of that event. */
    @Test
    fun gridEventRowOpensEditor() {
        prefs.saveEvent(trip)
        start()
        compose.onAllNodes(androidx.compose.ui.test.hasText(trip.name))[0].performClick()
        compose.mainClock.autoAdvance = false
        settle()
        shot("p10_04_editor_from_grid_row")
        assertEquals(trip.id, vm.editingEvent?.id)
        compose.onNodeWithText("Upravit událost").assertExists()
    }

    /** Info dialog in the viewer stays open after rotation (activity recreate). */
    @Test
    fun infoDialogSurvivesRecreate() {
        start()
        vm.stack.add(Screen.Viewer(Source.All, photos[3].id)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Další").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Informace").performClick(); compose.waitForIdle()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        shot("p10_05_info_after_recreate")
        compose.onNodeWithText("Informace").assertExists()
        compose.onNodeWithText("PXL_3.jpg").assertExists()
    }

    /** Double tap zooms; swiping to the next photo and back shows it unzoomed. */
    @Test
    fun viewerZoomThenSwipe() {
        start()
        vm.stack.add(Screen.Viewer(Source.All, photos[1].id)); compose.waitForIdle()
        compose.onRoot().performTouchInput { doubleClick(center) }
        compose.waitForIdle()
        shot("p10_06_viewer_zoomed")
        repeat(4) { compose.onRoot().performTouchInput { swipeLeft() }; compose.waitForIdle() }
        shot("p10_07_viewer_after_swipes")
        assertTrue(vm.returnTo != photos[1].id)
    }

    /** Clearing the queue, then changing the trip's album must not bring the photos back. */
    @Test
    fun clearedQueueStaysClearedAfterAlbumChange() {
        prefs.saveEvent(trip)
        start()
        assertTrue(prefs.queue.value.isNotEmpty())
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Vyprázdnit").performClick(); compose.waitForIdle()
        vm.saveEvent(prefs.events.value.single().copy(album = "Jine"))
        vm.refresh(); compose.waitForIdle(); idleMain()
        assertTrue(prefs.queue.value.isEmpty())
    }

    /** Selecting a day's photos in the calendar day grid and sending them queues only camera shots of that day. */
    @Test
    fun dayGridSelectAllSendQueuesThatDay() {
        start()
        val day = photos[21].day
        vm.stack.add(Screen.Grid(Source.Period("den", day, day))); compose.waitForIdle()
        compose.onNodeWithContentDescription("Vybrat celé album").performClick(); compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Poslat do cloudu").performClick()
        settle()
        shot("p10_08_day_send_dialog")
        compose.onNodeWithText("me@gmail.com").performClick()
        settle()
        compose.mainClock.autoAdvance = true
        val expected = photos.filter { it.day == day && it.isCameraShot }.map { it.key }.toSet()
        assertEquals(expected, prefs.queue.value.map { it.key }.toSet())
    }

    /**
     * The user selects all 1100 photos (long press, "Vybrat vše") and taps "Poslat do cloudu" to queue them
     * for their account. The queue has no size limit (only app intents do), yet the app refuses with a toast
     * and drops the whole selection.
     */
    @Test
    fun selectAllOver1000CanStillBeQueued() {
        val many = (0L until 1100L).map { Fixtures.photo(it, Fixtures.today.minusDays(it / 4)) }
        start(many)
        photoNode(0).performTouchInput { longClick() }; compose.waitForIdle()
        compose.onNodeWithContentDescription("Vybrat vše").performClick(); compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Poslat do cloudu").performClick()
        settle()
        shot("p10_09_send_1100")
        compose.onNodeWithText("Poslat 1100 do cloudu").assertExists()
    }
}

package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.CalendarZoom
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

/** Bigger and smaller tiles: pinch in the photo grid, three levels in the calendar. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class ZoomWalkthroughTest {
    private val setup = AppSetupRule { it.saveEvent(Fixtures.trip) }
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]

    private fun start() {
        vm.loadMedia = { photos }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value == photos }
        compose.waitForIdle()
    }

    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    /** Two fingers moving apart and back together, around the middle of the screen. */
    private fun pinch(apart: Boolean) = compose.onRoot().performTouchInput {
        val near = listOf(center - Offset(60f, 0f), center + Offset(60f, 0f))
        val far = listOf(center - Offset(400f, 0f), center + Offset(400f, 0f))
        val (from, to) = if (apart) near to far else far to near
        down(0, from[0])
        down(1, from[1])
        for (step in 1..10) {
            val t = step / 10f
            moveTo(0, from[0] + (to[0] - from[0]) * t)
            moveTo(1, from[1] + (to[1] - from[1]) * t)
        }
        up(0)
        up(1)
    }

    @Test
    fun pinchChangesGridColumns() {
        start()
        assertEquals(4, vm.prefs.gridColumns.value)
        pinch(apart = true)
        compose.waitForIdle()
        assertTrue("bigger tiles: ${vm.prefs.gridColumns.value}", vm.prefs.gridColumns.value < 4)
        repeat(3) { pinch(apart = true) }
        compose.waitForIdle()
        assertEquals(1, vm.prefs.gridColumns.value)
        shot("zoom_grid_1")
        repeat(7) { pinch(apart = false) }
        compose.waitForIdle()
        assertEquals(7, vm.prefs.gridColumns.value)
        shot("zoom_grid_small")
        // Pinching never opened a photo.
        assertTrue(vm.stack.none { it is Screen.Viewer })
    }

    @Test
    fun calendarLevels() {
        vm.prefs.setCalendarView(true)
        start()
        shot("zoom_calendar_month")

        compose.onNodeWithText("Dny").performClick()
        compose.waitForIdle()
        assertEquals(CalendarZoom.DAYS, vm.prefs.calendarZoom.value)
        shot("zoom_calendar_days")

        compose.onNodeWithText("Rok").performClick()
        compose.waitForIdle()
        assertEquals(CalendarZoom.YEAR, vm.prefs.calendarZoom.value)
        shot("zoom_calendar_year")

        pinch(apart = true)
        compose.waitForIdle()
        assertEquals(CalendarZoom.MONTH, vm.prefs.calendarZoom.value)
    }

    @Test
    fun dayRowOpensPhoto() {
        vm.prefs.setCalendarView(true)
        vm.prefs.setCalendarZoom(CalendarZoom.DAYS)
        start()
        compose.onRoot().performTouchInput { down(Offset(width * 0.3f, height * 0.4f)); up() }
        compose.waitForIdle()
        assertTrue(vm.stack.lastOrNull() is Screen.Viewer)
    }

    private fun swipeUpAt(xFraction: Float) = compose.onRoot().performTouchInput {
        val x = width * xFraction
        down(Offset(x, height * 0.75f))
        for (step in 1..20) {
            moveTo(Offset(x, height * (0.75f - 0.02f * step)))
            advanceEventTime(16)
        }
        up()
    }

    /** Dragging on empty space next to photos scrolls just like on the photos. */
    @Test
    fun emptySpaceScrollsGrid() {
        val many = (0L until 300L).map { Fixtures.photo(it, Fixtures.today.minusDays(it / 3)) }
        vm.loadMedia = { many }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value == many }
        compose.waitForIdle()
        val state = vm.gridState(com.justsomepren.gallery.data.Source.All)
        swipeUpAt(0.9f)
        compose.waitForIdle()
        assertTrue("grid did not move: ${state.firstVisibleItemIndex}/${state.firstVisibleItemScrollOffset}",
            state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0)
    }

    @Test
    fun emptySpaceScrollsCalendar() {
        val many = (0L until 300L).map { Fixtures.photo(it, Fixtures.today.minusDays(it * 2)) }
        vm.prefs.setCalendarView(true)
        vm.loadMedia = { many }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value == many }
        compose.waitForIdle()
        for (zoom in listOf(CalendarZoom.MONTH, CalendarZoom.DAYS, CalendarZoom.YEAR)) {
            vm.prefs.setCalendarZoom(zoom)
            compose.waitForIdle()
            compose.runOnUiThread { kotlinx.coroutines.runBlocking { vm.calendarState.scrollToItem(0) } }
            compose.waitForIdle()
            for (x in listOf(0.05f, 0.3f, 0.95f)) {
                compose.runOnUiThread { kotlinx.coroutines.runBlocking { vm.calendarState.scrollToItem(0) } }
                compose.waitForIdle()
                swipeUpAt(x)
                compose.waitForIdle()
                val state = vm.calendarState
                assertTrue("calendar $zoom did not move at $x", state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0)
            }
        }
    }

    /** Days work like the gallery: pinch changes the photo size, extra photos show as +N, scrollbar on the right. */
    @Test
    fun daysLikeGallery() {
        val many = (0L until 300L).map { Fixtures.photo(it, Fixtures.today.minusDays(it / 3)) }
        vm.prefs.setCalendarView(true)
        vm.prefs.setCalendarZoom(CalendarZoom.DAYS)
        vm.loadMedia = { many }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value == many }
        compose.waitForIdle()

        pinch(apart = true)
        compose.waitForIdle()
        assertEquals(3, vm.prefs.gridColumns.value)
        pinch(apart = true)
        compose.waitForIdle()
        assertEquals(2, vm.prefs.gridColumns.value)
        compose.onAllNodesWithText("+1").onFirst().assertExists()
        shot("zoom_days_2")

        compose.onAllNodesWithText("+1").onFirst().performClick()
        compose.waitForIdle()
        assertTrue(vm.stack.last() is Screen.Grid)
        compose.runOnUiThread { vm.stack.removeAt(vm.stack.lastIndex) }
        compose.waitForIdle()

        swipeUpAt(0.5f)
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("Rychlé posouvání").assertExists()
        shot("zoom_days_scroller")

        repeat(5) { pinch(apart = false) }
        compose.waitForIdle()
        assertEquals(7, vm.prefs.gridColumns.value)
        assertEquals(CalendarZoom.DAYS, vm.prefs.calendarZoom.value)
        pinch(apart = false)
        compose.waitForIdle()
        assertEquals(CalendarZoom.MONTH, vm.prefs.calendarZoom.value)
    }
}

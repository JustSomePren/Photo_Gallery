package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.geometry.Offset
import com.justsomepren.gallery.data.day
import java.time.LocalDate
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.QueueItem
import com.justsomepren.gallery.data.Source
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

/** Bug-hunt pass 11: sending tab states, albums/folders, video, favorites. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass11WalkthroughTest {
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

    @Test
    fun sendingTabWithEverything() {
        prefs.saveEvent(trip)
        start()
        val camera = photos.filter { it.isCameraShot }
        vm.enqueue(camera.drop(40).take(5), "me@gmail.com", "Rodina")
        prefs.markFailed(prefs.queue.value.take(2))
        prefs.setAccountProblem("me@gmail.com", true)
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        shot("p11_01_sending_full")
        compose.onNodeWithText("Přihlásit").assertExists()
        compose.onNodeWithText("Zkusit znovu").performClick(); compose.waitForIdle()
        assertTrue(prefs.failed.value.isEmpty())
    }

    @Test
    fun albumsTabFolderSelection() {
        start()
        compose.onNodeWithText("Alba").performClick(); compose.waitForIdle()
        shot("p11_02_albums")
        compose.onNodeWithText("Screenshots").performClick(); compose.waitForIdle()
        photoNode(0).performTouchInput { longClick() }; compose.waitForIdle()
        shot("p11_03_folder_selection")
        assertTrue(vm.stack.last() is Screen.Grid)
    }

    @Test
    fun favoritesTabAndVideo() {
        start()
        compose.onNodeWithText("Oblíbené").performClick(); compose.waitForIdle()
        shot("p11_04_favorites")
        compose.onNodeWithText("Fotky").performClick(); compose.waitForIdle()
        vm.stack.add(Screen.Viewer(Source.All, photos[5].id)); compose.waitForIdle()
        shot("p11_05_video_page")
        compose.onNodeWithContentDescription("Přehrát").performClick(); compose.waitForIdle()
        shot("p11_06_video_playing")
    }

    /**
     * A long trip (300 photos) is open on its photo screen. The user taps the pencil and renames it (or changes
     * its dates) and saves. Afterwards the fast-scroll handle shows up, but dragging it does not move the grid:
     * it still drives the grid state of the event as it was before the edit.
     */
    @Test
    fun fastScrollerWorksAfterEditingOpenEvent() {
        val many = (0L until 300L).map { Fixtures.photo(it, LocalDate.now().minusDays(it / 3)) }
        val longTrip = trip.copy(autoSend = false, account = null, album = null,
            startDay = many.last().day, endDay = many.first().day)
        prefs.saveEvent(longTrip)
        start(many)
        vm.stack.add(Screen.EventPhotos(longTrip.id)); compose.waitForIdle()
        vm.saveEvent(prefs.events.value.single().copy(name = "Velka cesta"))
        compose.waitForIdle()
        assertEquals("Velka cesta", prefs.events.value.single().name)
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = top + bottom * 0.3f) }
        compose.mainClock.advanceTimeBy(100)
        val handle = compose.onNodeWithContentDescription("Rychlé posouvání")
        handle.performTouchInput {
            down(center)
            repeat(20) { moveBy(Offset(0f, 150f)); advanceEventTime(30) }
            moveBy(Offset(0f, 3000f)); up()
        }
        compose.waitForIdle()
        shot("p11_07_fast_scroller_after_event_edit")
        val state = vm.eventGridState(prefs.events.value.single())
        val visible = state.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
        assertTrue("fast scroller did not move the event grid", many.takeLast(8).any { it.id in visible })
    }
}

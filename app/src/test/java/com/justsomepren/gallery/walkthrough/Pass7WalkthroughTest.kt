package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.QueueItem
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
import java.time.LocalDate

/** Bug-hunt pass 7: refusals per account, viewer while new photos arrive, viewer/grid flows. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass7WalkthroughTest {
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

    private fun photoNode(i: Int) = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))[i]
    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    /**
     * Google refused some photos for the personal account (picked by hand). Later the user creates a trip
     * event that sends the same days to the shared account. The shared account never refused anything,
     * so the photos must go there; they must not be skipped silently (they are not even in "failed" for it).
     */
    @Test
    fun refusalByOneAccountDoesNotBlockAutoSendToAnother() {
        start()
        val tripCamera = photos.filter { it.isCameraShot && trip.covers(it.day) }
        val refusedByMe = tripCamera.take(3)
        vm.enqueue(refusedByMe, "me@gmail.com", null)
        prefs.markFailed(prefs.queue.value)
        assertEquals(3, prefs.failed.value.size)

        vm.saveEvent(trip)
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        shot("p7_01_sending_refused_by_other_account")
        val sharedKeys = prefs.queue.value.filter { it.account == "shared@gmail.com" }.map { it.key }.toSet()
        val missing = refusedByMe.filter { it.key !in sharedKeys }
        assertTrue("photos refused by me@ are never sent to shared@: ${missing.map { it.name }}", missing.isEmpty())
    }

    /** A new photo (e.g. a screenshot) arrives while a photo is open: the viewer must keep showing the same photo. */
    @Test
    fun viewerStaysOnPhotoWhenNewPhotoArrives() {
        start()
        val open = photos[10]
        vm.stack.add(Screen.Viewer(Source.All, open.id))
        compose.waitForIdle()
        assertEquals(open.id, vm.returnTo)
        val newer = Fixtures.photo(500, LocalDate.now()).copy(date = photos[0].date + 60_000, dateTaken = photos[0].date + 60_000)
        start(listOf(newer) + photos)
        compose.waitForIdle()
        shot("p7_02_viewer_after_new_photo")
        assertEquals(open.id, vm.returnTo)
    }

    /** Swiping in the viewer of a calendar day stays inside that day; back returns to the day's grid. */
    @Test
    fun dayViewerSwipeStaysInDayAndBack() {
        start()
        val day = photos[30].day
        val dayPhotos = photos.filter { it.day == day }
        vm.stack.add(Screen.Grid(Source.Period("den", day, day)))
        compose.waitForIdle()
        photoNode(0).performClick(); compose.waitForIdle()
        repeat(dayPhotos.size + 1) { compose.onRoot().performTouchInput { swipeLeft() }; compose.waitForIdle() }
        assertEquals(dayPhotos.last().id, vm.returnTo)
        compose.onNodeWithContentDescription("Zpět").performClick(); compose.waitForIdle()
        assertTrue(vm.stack.last() is Screen.Grid)
        shot("p7_03_day_grid_after_viewer")
    }

    /** A hand-picked queue item stays when an overlapping auto-send event is switched off. */
    @Test
    fun handPickedPhotoStaysWhenEventTurnedOff() {
        start()
        val photo = photos.first { it.isCameraShot && trip.covers(it.day) }
        vm.enqueue(listOf(photo), "me@gmail.com", "Best")
        vm.saveEvent(trip)
        vm.saveEvent(prefs.events.value.single().copy(autoSend = false, account = null, album = null))
        assertEquals(listOf(QueueItem.of(photo, "me@gmail.com", "Best")), prefs.queue.value)
    }

    /**
     * Google refused a trip photo for shared@ (it is in "failed"). The user then sends a few photos to their
     * own account by hand, including that one. That must not make the event send it to shared@ again on its own.
     */
    @Test
    fun handPickToOtherAccountDoesNotRequeueRefusedEventPhoto() {
        prefs.saveEvent(trip)
        start()
        val refused = prefs.queue.value.first()
        prefs.markFailed(listOf(refused))
        val photo = photos.first { it.key == refused.key }
        assertTrue(prefs.queue.value.none { it.sentKey == refused.sentKey })

        vm.enqueue(listOf(photo), "me@gmail.com", null)
        vm.refresh(); compose.waitForIdle(); idleMain()
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        shot("p7_04_refused_requeued_after_hand_pick")
        assertTrue(
            "photo refused for shared@ went back to its queue automatically",
            prefs.queue.value.none { it.sentKey == refused.sentKey },
        )
    }

    /** Drag the fast scroller handle all the way down: the grid ends at the oldest month. */
    @Test
    fun fastScrollerDragReachesOldestPhotos() {
        val many = (0L until 400L).map { Fixtures.photo(it, LocalDate.now().minusDays(it)) }
        start(many)
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = top + bottom * 0.3f) }
        compose.mainClock.advanceTimeBy(100)
        val handle = compose.onNodeWithContentDescription("Rychlé posouvání")
        handle.performTouchInput {
            down(center)
            repeat(20) { moveBy(Offset(0f, 150f)); advanceEventTime(30) }
        }
        compose.waitForIdle()
        shot("p7_05_fast_scroller_dragging")
        handle.performTouchInput { moveBy(Offset(0f, 3000f)); up() }
        compose.waitForIdle()
        shot("p7_06_fast_scroller_end")
        val state = vm.gridState(Source.All)
        val visible = state.layoutInfo.visibleItemsInfo.map { it.key }.toSet()
        assertTrue("oldest photos not reached", many.takeLast(8).any { it.id in visible })
    }
}

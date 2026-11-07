package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.day
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import com.justsomepren.gallery.walkthrough.Fixtures.trip
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** Bug-hunt pass 13: editing an open event, fast scroller, large fonts. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass13WalkthroughTest {
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

    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    private val many = (0L until 300L).map { Fixtures.photo(it, LocalDate.now().minusDays(it / 3)) }
    private val longTrip = trip.copy(autoSend = false, account = null, album = null,
        startDay = many.last().day, endDay = many.first().day)

    private fun swipe() {
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.85f, endY = top + bottom * 0.2f) }
        compose.mainClock.advanceTimeBy(50)
    }

    /**
     * The user scrolls far down in a long event's photo screen, taps the pencil, renames it (or changes its
     * dates) and saves: the grid jumps back to the top, because its scroll state is looked up by the event's
     * Source (name + dates) instead of by the event id.
     */
    @Test
    fun editingOpenEventKeepsScrollPosition() {
        prefs.saveEvent(longTrip)
        start(many)
        vm.stack.add(Screen.EventPhotos(longTrip.id)); compose.waitForIdle()
        repeat(4) { swipe() }
        compose.waitForIdle()
        val before = vm.eventGridState(prefs.events.value.single()).firstVisibleItemIndex
        assertTrue("scrolled", before > 20)
        // The user taps the pencil, renames the event and saves.
        vm.saveEvent(prefs.events.value.single().copy(name = "Velka cesta"))
        compose.waitForIdle()
        shot("p13_01_after_edit")
        val after = vm.eventGridState(prefs.events.value.single()).firstVisibleItemIndex
        assertTrue("position $before lost after edit: now $after", after > 20)
    }

    @Test
    fun fastScrollerHandleFollowsGridWithoutEdit() = scrollerFollows(edit = false)

    /**
     * After the same edit the fast-scroll handle stays where it was while the new grid scrolls: FastScroller
     * remembers its derivedStateOf without the grid state as key, so it keeps reading the old state.
     */
    @Test
    fun fastScrollerHandleFollowsGridAfterEditingOpenEvent() = scrollerFollows(edit = true)

    private fun scrollerFollows(edit: Boolean) {
        prefs.saveEvent(longTrip)
        start(many)
        vm.stack.add(Screen.EventPhotos(longTrip.id)); compose.waitForIdle()
        if (edit) vm.saveEvent(prefs.events.value.single().copy(name = "Velka cesta"))
        compose.waitForIdle()
        repeat(5) { swipe() }
        val handle = compose.onNodeWithContentDescription("Rychlé posouvání")
        val top = handle.fetchSemanticsNode().boundsInRoot.top
        shot("p13_02_scroller_edit_$edit")
        val index = vm.eventGridState(prefs.events.value.single()).firstVisibleItemIndex
        assertTrue("grid scrolled", index > 40)
        assertTrue("handle stuck at top ($top) though grid is at $index", top > 400f)
    }
}

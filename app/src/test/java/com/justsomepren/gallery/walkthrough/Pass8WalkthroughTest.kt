package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.EVENT_COLORS
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.Source
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

/** Bug-hunt pass 8: refusals that outlive moving an event between accounts, fast scroller vs. taps. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass8WalkthroughTest {
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

    private fun photoNodes() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    /**
     * Google refused photo X for shared@. The user switches the trip to me@ by mistake and back to shared@.
     * me@ never refused X, yet a later auto-send event to me@ over the same days skips X silently:
     * it is neither queued for me@ nor shown among the failed photos.
     */
    @Test
    fun switchingEventAccountBackAndForthDoesNotLeaveFalseRefusal() {
        prefs.saveEvent(trip)
        start()
        val x = prefs.queue.value.first()
        prefs.markFailed(listOf(x))

        vm.saveEvent(prefs.events.value.single().copy(account = "me@gmail.com"))
        vm.saveEvent(prefs.events.value.single().copy(account = "shared@gmail.com"))
        assertTrue(prefs.failed.value.single().account == "shared@gmail.com")

        val other = Event(
            id = "mine", name = "Moje", color = EVENT_COLORS[3],
            startDay = trip.startDay, endDay = trip.endDay, autoSend = true, account = "me@gmail.com",
        )
        vm.saveEvent(other)
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        shot("p8_01_sending_after_account_switch")
        val forMe = (prefs.queue.value + prefs.failed.value).filter { it.account == "me@gmail.com" }.map { it.key }
        assertTrue("photo X silently skipped for me@ (never refused there)", x.key in forMe)
    }

    /** Right after a scroll the fast-scroller strip lies over the right column; a tap on a photo there must open it. */
    @Test
    fun tapOnRightColumnRightAfterScrollOpensPhoto() {
        val many = (0L until 400L).map { Fixtures.photo(it, LocalDate.now().minusDays(it)) }
        start(many)
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.7f, endY = bottom * 0.4f, durationMillis = 300) }
        repeat(5) { compose.mainClock.advanceTimeBy(100); idleMain() }
        shot("p8_02_after_scroll_strip_visible")
        val nodes = photoNodes().fetchSemanticsNodes()
        val rightIndex = nodes.indices.maxBy { nodes[it].boundsInRoot.right * 10_000 - nodes[it].boundsInRoot.top }
        val bounds = nodes[rightIndex].boundsInRoot
        photoNodes()[rightIndex].performTouchInput { click(Offset(width - 15f, height / 2f)) }
        repeat(5) { compose.mainClock.advanceTimeBy(200); idleMain() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        shot("p8_03_after_tap_right_column")
        assertTrue("tap at ${bounds.right - 15} on a right-column photo did not open the viewer", vm.stack.lastOrNull() is Screen.Viewer)
    }

    /** A second ordinary thumb flick near the right edge must scroll a little, not jump through years. */
    @Test
    fun secondSwipeNearRightEdgeScrollsNormally() {
        val many = (0L until 400L).map { Fixtures.photo(it, LocalDate.now().minusDays(it)) }
        start(many)
        val state = vm.gridState(Source.All)
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.7f, endY = bottom * 0.5f, durationMillis = 300) }
        repeat(5) { compose.mainClock.advanceTimeBy(100); idleMain() }
        val before = state.firstVisibleItemIndex
        compose.onRoot().performTouchInput {
            swipe(Offset(right - 15f, bottom * 0.75f), Offset(right - 15f, bottom * 0.55f), durationMillis = 300)
        }
        repeat(5) { compose.mainClock.advanceTimeBy(100); idleMain() }
        shot("p8_04_after_edge_swipe")
        val after = state.firstVisibleItemIndex
        compose.mainClock.autoAdvance = true
        assertTrue("edge swipe moved from $before to $after", after - before < 60)
    }

}

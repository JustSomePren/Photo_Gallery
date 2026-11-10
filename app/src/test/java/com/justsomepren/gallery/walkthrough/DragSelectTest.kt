package com.justsomepren.gallery.walkthrough

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
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

/** Hold a photo and drag over others to select them; photos are grouped by day. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class DragSelectTest {
    private val setup = AppSetupRule { prefs -> prefs.saveEvent(trip) }
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]

    private fun start() {
        vm.loadMedia = { photos }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value?.isNotEmpty() == true }
        compose.waitForIdle()
    }

    private fun photoCenter(i: Int): Offset =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))[i]
            .fetchSemanticsNode().boundsInRoot.center

    @Test
    fun holdAndDragSelectsAllPhotosOnTheWay() {
        start()
        val from = photoCenter(0)
        val to = photoCenter(6)
        compose.onRoot().performTouchInput {
            down(from)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(10) { step -> moveTo(from + (to - from) * ((step + 1) / 10f)); advanceEventTime(20) }
            up()
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/walkthrough/drag_select.png")
        val selected = vm.selection(Source.All.toString()).value
        assertTrue("dragging selected only ${selected.size}", selected.size >= 7)
    }

    @Test
    fun photosAreGroupedByDayAndCanBeSwitchedToMonths() {
        start()
        captureScreenRoboImage("build/outputs/walkthrough/grouped_by_day.png")
        assertTrue(compose.onAllNodesWithText("Dnes").fetchSemanticsNodes().isNotEmpty())
        vm.prefs.setGroupByDay(false)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("Dnes").fetchSemanticsNodes().size)
    }
}

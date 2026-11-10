package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.Source
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

/** Pass 25: trash, selecting a whole day, sideways selecting, pulling a photo down, the video player. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass25WalkthroughTest {
    private val setup = AppSetupRule()
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]
    private val selection get() = vm.selection(Source.All.toString()).value

    private fun start() {
        vm.loadMedia = { photos }
        vm.loadTrashed = { listOf(photos[1].copy(id = 9001)) }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value == photos }
        compose.waitForIdle()
    }

    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    private fun photoCenter(i: Int): Offset =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))[i]
            .fetchSemanticsNode().boundsInRoot.center

    @Test
    fun circleAtDaySelectsTheWholeDay() {
        start()
        compose.onNodeWithContentDescription("Vybrat: Dnes").performClick()
        compose.waitForIdle()
        val today = photos.filter { it.day == Fixtures.today.toEpochDay() }.map { it.id }.toSet()
        assertEquals(today, selection)
        compose.onNodeWithContentDescription("Zrušit výběr: Dnes").assertExists()
        shot("p25_01_day_selected")
    }

    @Test
    fun whileSelectingSidewaysSwipeSelectsWithoutHolding() {
        start()
        compose.onNodeWithContentDescription("Vybrat: Dnes").performClick()
        compose.waitForIdle()
        val from = photoCenter(3)
        val to = photoCenter(5)
        compose.onRoot().performTouchInput {
            down(from)
            repeat(10) { step -> moveTo(from + (to - from) * ((step + 1) / 10f)); advanceEventTime(16) }
            up()
        }
        compose.waitForIdle()
        assertTrue("selected ${selection.size}", selection.size >= 6)
    }

    @Test
    fun selectionCanGoToTrashAndTrashCanRestore() {
        start()
        compose.onNodeWithContentDescription("Vybrat: Dnes").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Do koše").assertExists()

        compose.onNodeWithContentDescription("Zrušit výběr").performClick()
        compose.onNodeWithText("Alba").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Koš (30 dní)").assertExists()
        compose.runOnUiThread { vm.stack.add(Screen.Grid(Source.Trash)) }
        compose.waitForIdle()
        shot("p25_02_trash")
        val first = photoCenter(0)
        compose.onRoot().performTouchInput { down(first); up() }
        compose.waitForIdle()
        // In the trash a tap selects for restoring, it does not open the photo.
        assertTrue(vm.stack.last() is Screen.Grid)
        compose.onNodeWithContentDescription("Obnovit").assertExists()
    }

    @Test
    fun pullingPhotoDownClosesIt() {
        start()
        compose.runOnUiThread { vm.stack.add(Screen.Viewer(Source.All, photos[0].id)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 60f)); advanceEventTime(30) }
            up()
        }
        compose.waitForIdle()
        assertTrue(vm.stack.none { it is Screen.Viewer })

        // A small pull springs back.
        compose.runOnUiThread { vm.stack.add(Screen.Viewer(Source.All, photos[0].id)) }
        compose.waitForIdle()
        compose.onRoot().performTouchInput {
            down(center)
            repeat(5) { moveBy(Offset(0f, 12f)); advanceEventTime(80) }
            up()
        }
        compose.waitForIdle()
        assertTrue(vm.stack.last() is Screen.Viewer)
    }

    @Test
    fun videoHasPlayerControls() {
        start()
        compose.runOnUiThread { vm.stack.add(Screen.Viewer(Source.All, photos[5].id)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Přehrát").assertExists()
        compose.onNodeWithContentDescription("Ztlumit").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Zapnout zvuk").assertExists()
        shot("p25_03_video")
    }
}

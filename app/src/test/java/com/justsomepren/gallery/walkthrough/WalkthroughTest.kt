package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.hasSetTextAction
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class WalkthroughTest {
    private val setup = AppSetupRule { prefs ->
        prefs.addAccount("shared@gmail.com")
        prefs.addAccount("me@gmail.com")
        prefs.saveEvent(trip)
        prefs.saveEvent(Fixtures.birthday)
    }
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

    private fun settle() {
        repeat(6) { compose.mainClock.advanceTimeBy(200); idleMain() }
    }

    private fun photoNode(i: Int) =
        compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.OnLongClick))[i]

    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    @Test
    fun gridSelectAndSendToQueue() {
        start()
        shot("01_grid")
        photoNode(5).performTouchInput { longClick() }
        compose.waitForIdle()
        shot("02_selected")
        compose.onNodeWithText("1").assertIsDisplayed()
        // The send dialog has a text field, which keeps Robolectric from ever idling: only look at it.
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Poslat do cloudu").performClick()
        settle()
        shot("03_send_dialog")
    }

    @Test
    fun monthHeaderSelectsMonthAndSelectAll() {
        start()
        val month = com.justsomepren.gallery.data.monthTitle(java.time.YearMonth.now())
        compose.onAllNodesWithText(month)[0].performClick()
        compose.waitForIdle()
        val inMonth = photos.count { java.time.YearMonth.from(it.date.let { m -> java.time.Instant.ofEpochMilli(m).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }) == java.time.YearMonth.now() }
        compose.onNodeWithText("$inMonth").assertIsDisplayed()
        compose.onNodeWithContentDescription("Vybrat vše").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("${photos.size}").assertIsDisplayed()
        shot("05_select_all")
    }

    @Test
    fun editingEventUpdatesItsOpenScreen() {
        start()
        vm.stack.add(Screen.EventPhotos(trip.id))
        compose.waitForIdle()
        shot("06_event_screen")
        compose.onNodeWithContentDescription("Upravit událost").assertIsDisplayed()
        // Same call the editor's "Uložit" makes, with a new name and a longer range.
        vm.saveEvent(trip.copy(name = "Vylet Brno", startDay = trip.startDay - 20))
        compose.waitForIdle()
        shot("07_event_screen_after_edit")
        compose.onAllNodesWithText("Vylet Brno")[0].assertIsDisplayed()
        compose.onNodeWithContentDescription("Upravit událost").assertIsDisplayed()
        // Deleting the event closes its screen.
        vm.deleteEvent(vm.prefs.events.value.first { it.id == trip.id })
        compose.waitForIdle()
        assertTrue(vm.stack.isEmpty())
    }

    @Test
    fun viewerSwipeAndBackKeepsPosition() {
        start()
        vm.stack.add(Screen.Viewer(Source.All, photos[40].id))
        compose.waitForIdle()
        shot("10_viewer")
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        shot("11_viewer_swiped")
        assertEquals(photos[41].id, vm.returnTo)
        compose.onNodeWithContentDescription("Zpět").performClick()
        compose.waitForIdle()
        shot("12_back_in_grid")
        assertTrue(vm.gridState(Source.All).firstVisibleItemIndex > 0)
    }

    @Test
    fun tabsCalendarAlbumsSettingsSending() {
        start()
        compose.onNodeWithContentDescription("Kalendář").performClick()
        compose.waitForIdle()
        shot("13_calendar")
        compose.onNodeWithText("Alba").performClick(); compose.waitForIdle()
        shot("14_albums")
        compose.onNodeWithText("Camera").performClick(); compose.waitForIdle()
        shot("15_folder")
        compose.onNodeWithContentDescription("Zpět").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        shot("16_sending")
        compose.onNodeWithText("Nastavení").performClick(); compose.waitForIdle()
        shot("17_settings")
        compose.onNodeWithText("Oblíbené").performClick(); compose.waitForIdle()
        shot("18_favorites")
        compose.onNodeWithText("Fotky").performClick(); compose.waitForIdle()
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.3f) }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(300)
        shot("19_calendar_scrolled")
    }
}

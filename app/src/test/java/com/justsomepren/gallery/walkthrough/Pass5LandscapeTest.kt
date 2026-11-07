package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.QueueItem
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import com.justsomepren.gallery.walkthrough.Fixtures.trip
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 5: the main screens in landscape, and the sending tab with every kind of state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w915dp-h412dp-land-xxhdpi")
class Pass5LandscapeTest {
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
    private val prefs get() = vm.prefs

    private fun start() {
        vm.loadMedia = { photos }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value?.size == photos.size }
        compose.waitForIdle()
    }

    private fun settle() {
        repeat(6) { compose.mainClock.advanceTimeBy(200); idleMain() }
    }

    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    @Test
    fun landscapeScreens() {
        start()
        shot("p5_l01_grid")
        compose.onNodeWithContentDescription("Kalendář").performClick(); compose.waitForIdle()
        shot("p5_l02_calendar")
        compose.onNodeWithContentDescription("Mřížka").performClick(); compose.waitForIdle()
        vm.stack.add(Screen.Viewer(Source.All, photos[4].id)); compose.waitForIdle()
        shot("p5_l03_viewer")
        compose.onNodeWithContentDescription("Zpět").performClick(); compose.waitForIdle()

        // Sending tab: queue to two albums, failed photos and an account that needs a new login.
        prefs.enqueue(photos.filter { it.isCameraShot }.drop(40).take(4).map { QueueItem.of(it, "me@gmail.com", "Rodina") })
        prefs.markFailed(prefs.queue.value.take(2))
        prefs.setAccountProblem("me@gmail.com", true)
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        shot("p5_l04_sending")
        compose.onNodeWithText("Je potřeba znovu přihlásit").assertExists()

        compose.onNodeWithText("Fotky").performClick(); compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        vm.editingEvent = prefs.events.value.first { it.id == trip.id }
        settle()
        shot("p5_l05_editor")
        compose.onNodeWithText("Uložit").assertIsDisplayed()  // the buttons stay reachable in landscape
    }
}

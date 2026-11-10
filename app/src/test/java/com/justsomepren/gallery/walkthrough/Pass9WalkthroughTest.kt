package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.data.MediaItem
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

/** Bug-hunt pass 9: photos picked by hand that an event had already queued. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass9WalkthroughTest {
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
     * The trip already queued photo P for shared@. The user long-presses P, taps "Poslat do cloudu" and picks shared@.
     * The app says "Nic nového k odeslání" (it is queued). Then the user switches the trip's auto-send off:
     * P was sent by hand, so it has to stay in the queue (like a photo hand-picked before the event existed).
     */
    @Test
    fun handPickOfPhotoAlreadyQueuedByEventSurvivesTurningEventOff() {
        prefs.saveEvent(trip)
        start()
        val index = vm.media.value!!.indexOfFirst { it.isCameraShot }
        val first = vm.media.value!![index]
        assertTrue(first.isCameraShot && trip.covers(first.day))
        assertTrue(prefs.queue.value.any { it.key == first.key && it.eventId == trip.id })

        photoNode(index).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Poslat do cloudu").performClick()
        settle()
        shot("p9_01_send_dialog_for_event_photo")
        compose.onNodeWithText("shared@gmail.com").performClick()
        settle()
        compose.mainClock.autoAdvance = true

        vm.saveEvent(prefs.events.value.single().copy(autoSend = false, account = null, album = null))
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        shot("p9_02_sending_after_event_off")
        assertEquals(
            "photo sent by hand was dropped with the event",
            listOf(first.key),
            prefs.queue.value.filter { it.account == "shared@gmail.com" }.map { it.key },
        )
    }
}

package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import com.justsomepren.gallery.walkthrough.Fixtures.trip
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 12 in landscape: the event editor and the send dialog over the viewer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w915dp-h412dp-land-xxhdpi")
class Pass12LandscapeTest {
    private val setup = AppSetupRule { prefs ->
        prefs.addAccount("shared@gmail.com")
        prefs.addAccount("me@gmail.com")
        prefs.saveEvent(trip)
    }
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]

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
    fun eventEditorAndSendDialog() {
        start()
        compose.mainClock.autoAdvance = false
        vm.editingEvent = vm.prefs.events.value.single()
        settle()
        shot("p12_l00_editor")
        vm.editingEvent = null
        settle()
        vm.stack.add(Screen.Viewer(Source.All, photos[2].id))
        settle()
        vm.dialogs.sending = listOf(photos[2])
        settle()
        shot("p12_l02_send_dialog")
        compose.mainClock.autoAdvance = true
        assertEquals(trip.album, vm.prefs.events.value.single().album)
    }
}

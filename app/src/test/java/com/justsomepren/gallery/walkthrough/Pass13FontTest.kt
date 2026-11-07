package com.justsomepren.gallery.walkthrough

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import com.justsomepren.gallery.walkthrough.Fixtures.trip
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 13: main screens with the system font size turned up. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi", fontScale = 1.3f)
class Pass13FontTest {
    private val setup = AppSetupRule { prefs ->
        prefs.addAccount("sharedxxo.family@gmail.com")
        prefs.addAccount("me.personal@gmail.com")
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

    private fun dialog(open: () -> Unit) {
        compose.mainClock.autoAdvance = false
        open()
        repeat(5) { compose.mainClock.advanceTimeBy(200); shadowOf(Looper.getMainLooper()).idle() }
    }

    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    @Test
    fun largeFontScreens() {
        prefs.saveEvent(trip.copy(account = "sharedxxo.family@gmail.com"))
        start()
        shot("p13f_01_grid")
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))[1].performTouchInput { longClick() }
        compose.waitForIdle()
        shot("p13f_02_selection")
        vm.stack.add(Screen.Viewer(Source.All, photos[1].id)); compose.waitForIdle()
        shot("p13f_03_viewer")
        vm.stack.clear(); compose.waitForIdle()
        vm.tab = 3; compose.waitForIdle()
        shot("p13f_04_sending")
        dialog { vm.editingEvent = prefs.events.value.single() }
        shot("p13f_05_editor")
    }

    @Test
    fun largeFontSendDialog() {
        start()
        dialog { vm.dialogs.sending = photos.take(3) }
        shot("p13f_06_send_dialog")
    }
}

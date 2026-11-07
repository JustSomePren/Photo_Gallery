package com.justsomepren.gallery.walkthrough

import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import com.justsomepren.gallery.walkthrough.Fixtures.trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 14: back navigation, event account changes, viewer album dialog, event deletion. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass14WalkthroughTest {
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
    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun backClearsSelectionThenLeavesFolderAndViewer() {
        start()
        // Fotky tab: selection, back clears it and the app stays open.
        photoNode(0).performTouchInput { longClick() }; compose.waitForIdle()
        compose.onNodeWithContentDescription("Zrušit výběr").assertExists()
        back()
        compose.onNodeWithContentDescription("Zrušit výběr").assertDoesNotExist()
        assertFalse(compose.activity.isFinishing)

        // Albums → folder → selection → back → back.
        compose.onNodeWithText("Alba").performClick(); compose.waitForIdle()
        compose.onAllNodesWithText("Camera")[0].performClick(); compose.waitForIdle()
        assertTrue(vm.stack.last() is Screen.Grid)
        photoNode(2).performTouchInput { longClick() }; compose.waitForIdle()
        back()
        assertEquals(1, vm.stack.size)
        compose.onNodeWithContentDescription("Zrušit výběr").assertDoesNotExist()

        // Viewer from the folder, back returns to the folder.
        photoNode(3).performClick(); compose.waitForIdle()
        assertTrue(vm.stack.last() is Screen.Viewer)
        back()
        assertTrue(vm.stack.last() is Screen.Grid)
        back()
        assertTrue(vm.stack.isEmpty())
        compose.onNodeWithText("Složky v telefonu").assertExists()
        shot("p14_01_back_chain")
    }

    @Test
    fun deletingEventFromItsPhotoScreenGoesBack() {
        prefs.saveEvent(trip.copy(autoSend = false, account = null, album = null))
        start()
        vm.stack.add(Screen.EventPhotos(trip.id)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Upravit událost").assertExists()
        // The editor's text field never lets Robolectric idle; this is what its "Smazat" button does.
        vm.editingEvent = prefs.events.value.single()
        vm.deleteEvent(prefs.events.value.single()); vm.editingEvent = null
        compose.waitForIdle()
        assertTrue(vm.stack.isEmpty())
        assertTrue(prefs.events.value.isEmpty())
        shot("p14_02_after_delete")
    }

    /**
     * Google refused photo X for shared@. The user switches the trip to me@: every other trip photo goes to me@,
     * but X is listed as failed for me@ and never queued, though me@ never refused it.
     */
    @Test
    fun switchingEventAccountQueuesPhotoRefusedOnlyByOldAccount() {
        prefs.saveEvent(trip)
        start()
        val x = prefs.queue.value.first()
        prefs.markFailed(listOf(x))
        vm.saveEvent(prefs.events.value.single().copy(account = "me@gmail.com"))
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        shot("p14_03_switch_account_refused")
        val queuedForMe = prefs.queue.value.filter { it.account == "me@gmail.com" }.map { it.key }
        assertTrue("other trip photos queued for me@", queuedForMe.size > 3)
        assertTrue("photo refused only by shared@ is blocked for me@", x.key in queuedForMe)
    }

    @Test
    fun viewerAddToExistingAlbum() {
        prefs.createAlbum("Vylet")
        start()
        val open = photos[4]
        vm.stack.add(Screen.Viewer(Source.All, open.id)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Další").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Přidat do alba").performClick(); compose.waitForIdle()
        shot("p14_04_viewer_album_picker")
        compose.onNodeWithText("Vylet").performClick(); compose.waitForIdle()
        assertEquals(listOf(open.key), prefs.albums.value.single().refs.map { it.key })
        assertTrue(vm.stack.last() is Screen.Viewer)
    }
}

package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 6: failed photos vs. editing an event in the editor, folders, favorites viewer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass6WalkthroughTest {
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
     * Google refused 3 photos of the trip. The user only fixes a typo in the event's name in the editor;
     * the album follows the name. The 3 refused photos must stay among the failed ones (with the new album),
     * not silently go back to the queue.
     */
    @Test
    fun renamingEventInEditorKeepsFailedPhotosFailed() {
        prefs.saveEvent(trip)
        start()
        val refused = prefs.queue.value.take(3)
        prefs.markFailed(refused)
        val refusedKeys = refused.map { it.sentKey }.toSet()

        // What "Uložit" saves after the user fixes the name: the album field follows the name (EventEditor.kt:87).
        // (Typing into the editor here never lets Robolectric idle, so the save is done with the same call.)
        vm.saveEvent(prefs.events.value.single().copy(name = "Dovča Praha", album = "Dovča Praha"))
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        shot("p6_02_sending_after_rename")
        val requeued = prefs.queue.value.filter { it.sentKey in refusedKeys }
        assertTrue("refused photos went back to the queue: ${requeued.size}", requeued.isEmpty())
        assertEquals(3, prefs.failed.value.size)
        assertTrue(prefs.failed.value.all { it.album == "Dovča Praha" })
    }

    /** Same as above through the call "Uložit" makes, after typing another album name. */
    @Test
    fun changingEventAlbumKeepsFailedPhotosFailed() {
        prefs.saveEvent(trip)
        start()
        val refused = prefs.queue.value.take(3)
        prefs.markFailed(refused)
        vm.saveEvent(prefs.events.value.single().copy(album = "Praha 2026"))
        val keys = refused.map { it.sentKey }.toSet()
        assertEquals(0, prefs.queue.value.count { it.sentKey in keys })
        assertEquals(3, prefs.failed.value.size)
    }

    /** Only a color change: nothing about the queue or failed photos may change. */
    @Test
    fun changingEventColorLeavesQueueAndFailedAlone() {
        prefs.saveEvent(trip)
        start()
        prefs.markFailed(prefs.queue.value.take(3))
        val queue = prefs.queue.value
        val failed = prefs.failed.value
        vm.saveEvent(prefs.events.value.single().copy(color = Fixtures.birthday.color))
        assertEquals(queue, prefs.queue.value)
        assertEquals(failed, prefs.failed.value)
    }

    @Test
    fun screenshotsFolderSelectAllSendQueuesNothing() {
        start()
        compose.onNodeWithText("Alba").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Screenshots").performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Vybrat celé album").performClick(); compose.waitForIdle()
        val shots = photos.count { !it.isCameraShot }
        compose.onNodeWithText("$shots").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Poslat do cloudu").performClick()
        settle()
        shot("p6_03_screenshots_send_dialog")
        compose.onNodeWithText("me@gmail.com").performClick()
        settle()
        assertTrue(prefs.queue.value.isEmpty())
    }

    @Test
    fun favoritesViewerSwipeAndBack() {
        start()
        compose.onNodeWithText("Oblíbené").performClick(); compose.waitForIdle()
        val favorites = photos.filter { it.isFavorite }
        photoNode(1).performClick(); compose.waitForIdle()
        val top = vm.stack.last() as Screen.Viewer
        assertEquals(Source.Favorites, top.source)
        assertEquals(favorites[1].id, top.startId)
        shot("p6_04_favorites_viewer")
        compose.onNodeWithContentDescription("Zpět").performClick(); compose.waitForIdle()
        assertTrue(vm.stack.isEmpty())
        assertEquals(1, vm.tab)
        // Long press in favorites still selects.
        photoNode(0).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Zrušit výběr").assertIsDisplayed()
        compose.onAllNodesWithText("1")[0].assertIsDisplayed()
    }
}

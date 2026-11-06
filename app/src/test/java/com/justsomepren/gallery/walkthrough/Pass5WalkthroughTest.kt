package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/** Bug-hunt pass 5: failed photos vs. event changes, dialogs and selection across rotation. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass5WalkthroughTest {
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
    private fun tripKeys() = photos.filter { it.isCameraShot && trip.covers(it.day) }.map { it.key }.toSet()

    /** The worker marked some of the event's photos failed; then the user turns the event's sending off. */
    @Test
    fun retryAfterTurningEventOffDoesNotSendItsPhotos() {
        prefs.saveEvent(trip)
        start()
        prefs.markFailed(prefs.queue.value.take(3))
        assertEquals(3, prefs.failed.value.size)
        vm.saveEvent(prefs.events.value.single().copy(autoSend = false, account = null, album = null))
        assertTrue(prefs.queue.value.isEmpty())

        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        shot("p5_01_failed_after_event_off")
        // The event no longer sends, so its failed photos are gone and there is nothing to retry.
        assertTrue(prefs.failed.value.isEmpty())
        compose.onNodeWithText("Zkusit znovu").assertDoesNotExist()
        vm.retryFailed()
        assertTrue(
            "photos of the turned-off event are queued again: ${prefs.queue.value.map { it.album }}",
            prefs.queue.value.none { it.key in tripKeys() },
        )
    }

    @Test
    fun retryAfterDeletingEventDoesNotSendItsPhotos() {
        prefs.saveEvent(trip)
        start()
        prefs.markFailed(prefs.queue.value.take(3))
        vm.deleteEvent(prefs.events.value.single())
        vm.retryFailed()
        assertTrue(prefs.queue.value.none { it.key in tripKeys() })
    }

    @Test
    fun retryAfterChangingEventAlbumUsesNewAlbum() {
        prefs.saveEvent(trip)
        start()
        prefs.markFailed(prefs.queue.value.take(3))
        vm.saveEvent(prefs.events.value.single().copy(album = "Praha 2026"))
        vm.retryFailed()
        val retried = prefs.queue.value.filter { it.key in tripKeys() }
        assertTrue(retried.isNotEmpty())
        assertTrue(retried.map { it.album }.toString(), retried.all { it.album == "Praha 2026" })
    }

    @Test
    fun sendDialogSurvivesRotationAndKeepsSuggestedAlbum() {
        prefs.saveEvent(trip)
        start()
        photoNode(0).performTouchInput { longClick() }
        photoNode(1).performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Poslat do cloudu").performClick()
        settle()
        compose.activityRule.scenario.recreate()
        settle()
        shot("p5_03_send_dialog_after_rotation")
        compose.onNodeWithText("Poslat 2 do cloudu").assertIsDisplayed()
        compose.onNode(hasText("Dovca Praha") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        val chosen = vm.dialogs.sending!!
        compose.onNodeWithText("me@gmail.com").performClick()
        settle()
        val mine = prefs.queue.value.filter { it.account == "me@gmail.com" }
        assertEquals(chosen.map { "${it.id} ${it.relativePath}" }.toString(), chosen.count { it.isCameraShot }, mine.size)
        assertTrue(mine.isNotEmpty())
        assertTrue(mine.all { it.album == "Dovca Praha" })
    }

    @Test
    fun selectionAndNewAlbumDialogSurviveRotation() {
        start()
        photoNode(0).performTouchInput { longClick() }
        photoNode(3).performClick()
        compose.waitForIdle()
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Další").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Přidat do alba").performClick(); compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Nové album…").performClick()
        settle()
        compose.activityRule.scenario.recreate()
        settle()
        shot("p5_04_new_album_after_rotation")
        compose.onNodeWithText("Nové album").assertIsDisplayed()
        compose.onNodeWithText("Vytvořit").assertExists()
    }

    @Test
    fun eventEditorSurvivesRotation() {
        prefs.saveEvent(trip)
        start()
        compose.mainClock.autoAdvance = false
        vm.editingEvent = prefs.events.value.single()
        settle()
        compose.activityRule.scenario.recreate()
        settle()
        shot("p5_05_editor_after_rotation")
        compose.onNodeWithText("Upravit událost").assertIsDisplayed()
        compose.onNodeWithText("Uložit").assertExists()
    }
}

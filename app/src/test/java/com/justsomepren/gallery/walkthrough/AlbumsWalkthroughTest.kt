package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
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
class AlbumsWalkthroughTest {
    private val setup = AppSetupRule { prefs ->
        prefs.addAccount("shared@gmail.com")
        prefs.saveEvent(trip)
        prefs.createAlbum("Best", photos.take(3))
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

    private fun photoNode(i: Int) = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))[i]
    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    @Test
    fun addToAlbumRemoveFromAlbumDeleteAlbum() {
        start()
        photoNode(10).performTouchInput { longClick() }
        photoNode(11).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Další").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Přidat do alba").performClick()
        compose.waitForIdle()
        shot("20_album_picker")
        compose.onNodeWithText("Best").performClick()
        compose.waitForIdle()
        assertEquals(5, vm.prefs.albums.value.single().refs.size)

        compose.onNodeWithText("Alba").performClick(); compose.waitForIdle()
        shot("21_albums_with_own")
        compose.onNodeWithText("Best").performClick(); compose.waitForIdle()
        shot("22_album_open")
        photoNode(0).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Další").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Odebrat z alba").performClick(); compose.waitForIdle()
        assertEquals(4, vm.prefs.albums.value.single().refs.size)
        compose.onNodeWithContentDescription("Další").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Smazat album").performClick(); compose.waitForIdle()
        shot("23_delete_album_confirm")
        compose.onNodeWithText("Smazat").performClick(); compose.waitForIdle()
        assertTrue(vm.prefs.albums.value.isEmpty())
        assertTrue(vm.stack.isEmpty())
        shot("24_after_delete")
    }

    @Test
    fun calendarEventChipOpensEventPhotosAndDayOpens() {
        start()
        vm.prefs.setCalendarView(true)
        compose.waitForIdle()
        // [0] is the running-event chip above the calendar (opens the editor), [1] the one in the month header.
        compose.onAllNodesWithText(trip.name + " → shared")[1].performClick()
        compose.waitForIdle()
        shot("25_event_from_calendar")
        assertEquals(com.justsomepren.gallery.Screen.EventPhotos(trip.id), vm.stack.last())
        compose.onNodeWithContentDescription("Zpět").performClick(); compose.waitForIdle()
        assertTrue(vm.stack.isEmpty())
    }

    @Test
    fun viewerMenuInfoAndSendDialog() {
        start()
        photoNode(2).performClick()
        compose.waitForIdle()
        shot("26_viewer_open")
        compose.onNodeWithContentDescription("Další").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Informace").performClick(); compose.waitForIdle()
        shot("28_viewer_info")
        compose.onNodeWithText("Zavřít").performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Zpět").performClick(); compose.waitForIdle()
        assertTrue(vm.stack.isEmpty())
    }
}

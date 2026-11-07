package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.Source
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

/** Bug-hunt pass 20: photos deleted elsewhere (e.g. in Google Photos) while the viewer shows them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass20WalkthroughTest {
    private val setup = AppSetupRule { prefs -> prefs.addAccount("shared@gmail.com") }
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]

    private fun load(media: List<MediaItem>) {
        vm.loadMedia = { media }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value == media }
        compose.waitForIdle()
    }

    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    private fun swipe(left: Boolean) {
        compose.onRoot().performTouchInput { if (left) swipeLeft() else swipeRight() }
        compose.waitForIdle()
    }

    /** The last photo of an album is open and gets deleted: the viewer shows the one before it, no crash. */
    @Test
    fun deletingLastPhotoWhileViewingIt() {
        load(photos)
        vm.prefs.createAlbum("Výlet", listOf(photos[1], photos[2], photos[3]))
        val album = vm.prefs.albums.value.single()
        val source = Source.Album(album.id, album.name)
        vm.stack.add(Screen.Grid(source)); compose.waitForIdle()
        vm.stack.add(Screen.Viewer(source, photos[3].id)); compose.waitForIdle()
        load(photos - photos[3])
        shot("p20_01_viewer_after_deleting_last")
        assertTrue(vm.stack.last() is Screen.Viewer)
        compose.onNodeWithContentDescription("Zpět").assertExists()
        assertEquals(photos[2].id, vm.returnTo)
        swipe(left = false)
        assertEquals(photos[1].id, vm.returnTo)
    }

    /** A photo in the middle is open and gets deleted: swiping still reaches its neighbours. */
    @Test
    fun deletingMiddlePhotoWhileViewingIt() {
        load(photos)
        vm.stack.add(Screen.Viewer(Source.All, photos[5].id)); compose.waitForIdle()
        load(photos - photos[5])
        shot("p20_02_viewer_after_deleting_middle")
        val shown = vm.returnTo
        assertTrue(shown == photos[4].id || shown == photos[6].id)
        swipe(left = true)
        swipe(left = false)
        swipe(left = false)
        assertTrue(vm.returnTo != photos[5].id)
    }

    /** Every photo of the open viewer is gone: it closes and the grid is back. */
    @Test
    fun deletingAllPhotosOfViewerClosesIt() {
        load(photos)
        vm.prefs.createAlbum("Výlet", listOf(photos[1]))
        val album = vm.prefs.albums.value.single()
        val source = Source.Album(album.id, album.name)
        vm.stack.add(Screen.Grid(source)); compose.waitForIdle()
        vm.stack.add(Screen.Viewer(source, photos[1].id)); compose.waitForIdle()
        load(photos - photos[1])
        shot("p20_03_album_after_viewer_emptied")
        assertTrue(vm.stack.last() is Screen.Grid)
    }
}

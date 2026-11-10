package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 17: opening the same photo in the viewer a second time. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass17WalkthroughTest {
    private val setup = AppSetupRule { prefs -> prefs.addAccount("shared@gmail.com") }
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]

    private fun start(media: List<MediaItem> = photos) {
        vm.loadMedia = { media }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value == media }
        compose.waitForIdle()
    }

    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun swipe(left: Boolean) {
        compose.onRoot().performTouchInput { if (left) swipeLeft() else swipeRight() }
        compose.waitForIdle()
    }

    /** Control: opening a photo, going back and opening it again with nothing changed works. */
    @Test
    fun reopeningSamePhotoUnchangedAlbum() {
        start()
        vm.prefs.createAlbum("Výlet", listOf(photos[1], photos[2], photos[3]))
        val album = vm.prefs.albums.value.single()
        val source = Source.Album(album.id, album.name)
        vm.stack.add(Screen.Grid(source)); compose.waitForIdle()
        vm.stack.add(Screen.Viewer(source, photos[2].id)); compose.waitForIdle()
        swipe(left = true)
        assertEquals(photos[3].id, vm.returnTo)
    }

    /**
     * The user opens a photo of an album, goes back, removes another photo from the album, and opens the same
     * photo again: swiping still shows the photo that is no longer in the album (the viewer reuses the photo list
     * of the first opening, because the old Screen.Viewer equals the new one).
     */
    @Test
    fun reopeningSamePhotoAfterAlbumChangeShowsCurrentAlbum() {
        start()
        vm.prefs.createAlbum("Výlet", listOf(photos[1], photos[2], photos[3]))
        val album = vm.prefs.albums.value.single()
        val source = Source.Album(album.id, album.name)
        vm.stack.add(Screen.Grid(source)); compose.waitForIdle()
        vm.stack.add(Screen.Viewer(source, photos[2].id)); compose.waitForIdle()
        back()
        assertEquals(Screen.Grid(source), vm.stack.last())

        vm.prefs.removeFromAlbum(album.id, listOf(photos[3])); compose.waitForIdle()
        shot("p17_01_album_after_removing_photo")
        vm.stack.add(Screen.Viewer(source, photos[2].id)); compose.waitForIdle()
        swipe(left = true)
        shot("p17_02_viewer_swiped_to_removed_photo")
        assertEquals("viewer showed a photo that is no longer in the album", photos[2].id, vm.returnTo)
    }

    /**
     * Fotky: the user opens the newest photo, goes back, takes a new photo, and opens the same photo again.
     * Swiping to the newer side should reach the new photo, but the viewer still has the old list.
     */
    @Test
    fun reopeningSamePhotoAfterNewPhotoShowsNewPhoto() {
        start()
        vm.stack.add(Screen.Viewer(Source.All, photos[0].id)); compose.waitForIdle()
        back()
        val fresh = Fixtures.photo(500, Fixtures.today).copy(date = photos[0].date + 60_000, dateTaken = photos[0].date + 60_000)
        start(listOf(fresh) + photos)
        vm.stack.add(Screen.Viewer(Source.All, photos[0].id)); compose.waitForIdle()
        swipe(left = false)
        shot("p17_03_viewer_reopened_after_new_photo")
        assertEquals("new photo missing from the viewer", fresh.id, vm.returnTo)
    }
}

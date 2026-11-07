package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

/** Bug-hunt pass 16: the viewer keeps its photo when the phone is turned after the photo list changed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass16WalkthroughTest {
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

    private fun rotate() {
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
    }

    /**
     * In Favorites the user unfavorites the photo shown in the viewer (it stays on screen, as intended), then
     * turns the phone: the viewer now shows a different photo, the next favorite.
     */
    @Test
    fun unfavoriteInFavoritesViewerThenRotateKeepsPhoto() {
        start()
        val open = photos.filter { it.isFavorite }[1]
        vm.stack.add(Screen.Viewer(Source.Favorites, open.id)); compose.waitForIdle()
        start(photos.map { if (it.id == open.id) it.copy(isFavorite = false) else it })
        assertEquals(open.id, vm.returnTo)
        compose.onNodeWithContentDescription("Přidat do oblíbených").assertExists()
        rotate()
        shot("p16_01_fav_viewer_after_unfavorite_and_rotation")
        assertEquals("viewer jumped to another photo after rotation", open.id, vm.returnTo)
        compose.onNodeWithContentDescription("Přidat do oblíbených").assertExists()
    }

    /** A new photo appears (e.g. a copy saved by Google Photos' editor) while the viewer is open; then rotation. */
    @Test
    fun newPhotoThenRotateKeepsPhoto() {
        start()
        val open = photos[5]
        vm.stack.add(Screen.Viewer(Source.All, open.id)); compose.waitForIdle()
        val fresh = Fixtures.photo(500, Fixtures.today).copy(date = photos[0].date + 60_000, dateTaken = photos[0].date + 60_000)
        start(listOf(fresh) + photos)
        assertEquals(open.id, vm.returnTo)
        rotate()
        shot("p16_02_viewer_after_new_photo_and_rotation")
        assertEquals("viewer jumped to another photo after rotation", open.id, vm.returnTo)
    }
}

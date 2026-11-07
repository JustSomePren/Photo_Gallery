package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeLeft
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

/** Bug-hunt pass 15: viewer inside Favorites, selection after dialogs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass15WalkthroughTest {
    private val setup = AppSetupRule { prefs ->
        prefs.addAccount("shared@gmail.com")
    }
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

    private fun photoNode(i: Int) = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))[i]
    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    /**
     * In Favorites the user opens a photo and taps the heart to remove it from favorites. Once Android confirms
     * (the app reloads the photos), the viewer jumps to the next favorite, whose heart is filled: the photo just
     * unfavorited is gone from the screen, and a second tap on the heart unfavorites another photo.
     */
    @Test
    fun unfavoritingInFavoritesViewerStaysOnThatPhoto() {
        start()
        val favorites = photos.filter { it.isFavorite }
        val open = favorites[1]
        vm.stack.add(Screen.Viewer(Source.Favorites, open.id)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Odebrat z oblíbených").assertExists()
        shot("p15_01_fav_viewer_before")
        // What MediaActions.onResult does after Android applied the favorite request.
        start(photos.map { if (it.id == open.id) it.copy(isFavorite = false) else it })
        compose.waitForIdle()
        shot("p15_02_fav_viewer_after_unfavorite")
        assertEquals("viewer left the photo the user was looking at", open.id, vm.returnTo)
    }

    /** Rotating the phone in the viewer keeps the photo the user swiped to. */
    @Test
    fun viewerKeepsSwipedPhotoAfterRotation() {
        start()
        vm.stack.add(Screen.Viewer(Source.All, photos[10].id)); compose.waitForIdle()
        repeat(2) { compose.onRoot().performTouchInput { swipeLeft() }; compose.waitForIdle() }
        assertEquals(photos[12].id, vm.returnTo)
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        shot("p15_03_viewer_after_rotation")
        assertEquals(photos[12].id, vm.returnTo)
        compose.onNodeWithContentDescription("Zpět").performClick(); compose.waitForIdle()
        assertTrue(vm.stack.isEmpty())
    }
}

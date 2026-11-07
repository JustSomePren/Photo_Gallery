package com.justsomepren.gallery.walkthrough

import android.net.Uri
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.EVENT_COLORS
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.ui.ImageCache
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import com.justsomepren.gallery.walkthrough.Fixtures.today
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 18: edited pictures reload; events inside other events in the grid. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass18WalkthroughTest {
    private val loads = mutableMapOf<Uri, Int>()
    private val setup = AppSetupRule { prefs ->
        prefs.addAccount("shared@gmail.com")
        ImageCache.fake = { uri -> loads[uri] = (loads[uri] ?: 0) + 1; fakePhoto(uri) }
        // The cache lives as long as the test JVM; start without pictures of earlier tests.
        ImageCache.forget(photos.map { it.uri })
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

    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    private fun edited(id: Long) = photos.map { if (it.id == id) it.copy(modified = 99, size = 3_100_000) else it }

    /** Control: a photo edited elsewhere (same id, new date modified) is loaded again in the grid. */
    @Test
    fun editedPhotoReloadsInGrid() {
        start()
        val uri = photos[1].uri
        val before = loads[uri] ?: 0
        assertTrue(before > 0)
        start(edited(photos[1].id))
        assertTrue("grid kept the old picture of an edited photo", (loads[uri] ?: 0) > before)
    }

    /** Control: the same in the open viewer (the user comes back from Google Photos' editor). */
    @Test
    fun editedPhotoReloadsInViewer() {
        start()
        vm.stack.add(Screen.Viewer(Source.All, photos[1].id)); compose.waitForIdle()
        val uri = photos[1].uri
        val before = loads[uri] ?: 0
        start(edited(photos[1].id))
        assertTrue("viewer kept the old picture of an edited photo", (loads[uri] ?: 0) > before)
    }

    /**
     * The user has a long event ("Léto", created first) and later adds a short trip inside it ("Praha").
     * The README promises a colored row with the name where an event's photos begin, but the grid always
     * takes the first event covering a day, so "Praha" never gets a row, a strip or the floating label.
     */
    @Test
    fun eventInsideLongerEventHasItsOwnRowInGrid() {
        vm.prefs.saveEvent(Event(id = "leto", name = "Leto", color = EVENT_COLORS[3], startDay = today.minusDays(25).toEpochDay(), endDay = today.minusDays(5).toEpochDay()))
        vm.prefs.saveEvent(Event(id = "praha", name = "Praha", color = EVENT_COLORS[0], startDay = today.minusDays(12).toEpochDay(), endDay = today.minusDays(10).toEpochDay()))
        start()
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Leto"))
        compose.waitForIdle()
        shot("p18_01_grid_event_inside_event")
        val found = runCatching {
            compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Praha"))
        }.isSuccess
        compose.waitForIdle()
        shot("p18_02_grid_inner_event")
        assertTrue("inner event Praha has no row in the grid", found)
    }
}

package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.data.EVENT_COLORS
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.day
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import com.justsomepren.gallery.walkthrough.Fixtures.today
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 19: auto-send events inside other auto-send events. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass19WalkthroughTest {
    private val account = "me@gmail.com"
    private val leto = Event(
        id = "leto", name = "Leto", color = EVENT_COLORS[3],
        startDay = today.minusDays(25).toEpochDay(), endDay = today.minusDays(5).toEpochDay(),
        autoSend = true, account = account, album = "Leto",
    )
    private val praha = Event(
        id = "praha", name = "Praha", color = EVENT_COLORS[0],
        startDay = today.minusDays(12).toEpochDay(), endDay = today.minusDays(10).toEpochDay(),
        autoSend = true, account = account, album = "Praha",
    )
    private val setup = AppSetupRule { prefs ->
        prefs.addAccount(account)
        prefs.saveEvent(leto)
        prefs.saveEvent(praha)
    }
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]

    private fun start() {
        vm.loadMedia = { photos }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value == photos }
        compose.waitForIdle()
    }

    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    private val prahaCamera get() = photos.filter { it.isCameraShot && praha.covers(it.day) }

    /** Control: every camera photo of both events is queued once, to the one account. */
    @Test
    fun overlappingAutoEventsQueueEachPhotoOnce() {
        start()
        val queue = vm.prefs.queue.value
        val wanted = photos.filter { it.isCameraShot && leto.covers(it.day) }
        assertTrue(prahaCamera.isNotEmpty())
        assertEquals(wanted.map { it.key }.toSet(), queue.map { it.key }.toSet())
        assertEquals(queue.size, queue.map { it.sentKey }.toSet().size)
    }

    /**
     * "Praha" (album Praha) lies inside "Leto" (album Leto), both sending to the same account. The grid shows
     * the Praha photos under the Praha row with its strip (the more specific event wins), but they are queued
     * into the album "Leto" because Leto was created first; nothing ever reaches the Praha album.
     */
    @Test
    fun photosOfInnerEventGoToItsAlbum() {
        start()
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Praha"))
        compose.waitForIdle()
        shot("p19_01_grid_praha_inside_leto")
        compose.onNodeWithText("Odesílání").performClick()
        compose.waitForIdle()
        shot("p19_02_sending_tab_albums")
        val keys = prahaCamera.map { it.key }.toSet()
        val albums = vm.prefs.queue.value.filter { it.key in keys }.map { it.album }.toSet()
        assertEquals(setOf("Praha"), albums)
    }
}

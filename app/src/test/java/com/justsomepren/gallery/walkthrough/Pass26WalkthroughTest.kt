package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.BatteryState
import com.justsomepren.gallery.data.CAMERA_AUTO
import com.justsomepren.gallery.data.QueueItem
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.ui.queueStatus
import com.justsomepren.gallery.ui.waitReason
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class Pass26WalkthroughTest {
    private val setup = AppSetupRule { prefs ->
        prefs.addAccount("me@gmail.com")
        prefs.addAccount("shared@gmail.com")
        prefs.createAlbum("Léto")
    }
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]
    private val camera = photos.filter { it.isCameraShot }

    private fun start() {
        vm.loadMedia = { photos }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value?.isNotEmpty() == true }
        compose.waitForIdle()
    }

    private fun photoNode(i: Int) = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))[i]
    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    // Dialogs never get idle under Robolectric, so the clock is moved by hand while one is open
    private fun settle() {
        repeat(6) { compose.mainClock.advanceTimeBy(200); idleMain() }
    }

    @Test
    fun newAlbumStartsWithPickingPhotos() {
        start()
        compose.onNodeWithText("Alba").performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Nové album").performClick(); compose.waitForIdle()
        assertEquals(Screen.PickForAlbum(null), vm.stack.last())
        // A tap picks instead of opening the photo
        photoNode(1).performClick()
        photoNode(2).performClick()
        compose.waitForIdle()
        shot("260_pick_for_new_album")
        compose.onNodeWithText("Vybráno 2").assertExists()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Další").performClick()
        settle()
        shot("261_name_new_album")
        compose.onNodeWithText("Vytvořit").assertExists()
    }

    @Test
    fun emptyAlbumGetsPhotosFromItsOwnScreen() {
        start()
        compose.onNodeWithText("Alba").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Léto").performClick(); compose.waitForIdle()
        shot("262_empty_album")
        compose.onNodeWithText("Album je prázdné", substring = true).assertExists()
        compose.onNodeWithContentDescription("Přidat fotky").performClick(); compose.waitForIdle()
        photoNode(3).performClick()
        photoNode(4).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Přidat").performClick(); compose.waitForIdle()
        assertEquals(2, vm.prefs.albums.value.single().refs.size)
        assertTrue(vm.stack.last() is Screen.Grid)
        shot("263_album_with_photos")
    }

    @Test
    fun holdingTheCloudSendsToTheSecondAccount() {
        start()
        photoNode(1).performTouchInput { longClick() }
        photoNode(2).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Poslat do cloudu").performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(setOf("shared@gmail.com"), vm.prefs.queue.value.map { it.account }.toSet())
        assertEquals(2, vm.prefs.queue.value.size)
        shot("264_queued_badges")
    }

    @Test
    fun queueOverviewOpensThePhotosAndRemovesThem() {
        start()
        vm.enqueue(camera.take(3), "me@gmail.com", "Výlet")
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        shot("265_sending_overview")
        compose.onNodeWithText("me@gmail.com · Výlet: 3").performScrollTo().performClick(); compose.waitForIdle()
        assertEquals(Screen.Grid(Source.Uploads("me@gmail.com")), vm.stack.last())
        photoNode(0).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Další").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Odebrat z fronty").performClick(); compose.waitForIdle()
        assertEquals(2, vm.prefs.queue.value.size)
    }

    @Test
    fun waitReasonSaysWhatHoldsTheQueue() {
        val queued = listOf(QueueItem.of(camera[0], "me@gmail.com", null))
        val full = BatteryState(90, charging = false, powerSave = false)
        val low = BatteryState(20, charging = false, powerSave = false)
        assertEquals("čeká na Wi-Fi", waitReason(queued, onWifi = false, mobileData = false, full, 70, emptySet()))
        assertNull(waitReason(queued, onWifi = false, mobileData = true, full, 70, emptySet()))
        val camera = queued.map { it.copy(eventId = CAMERA_AUTO) }
        assertEquals("čeká na Wi-Fi", waitReason(camera, onWifi = false, mobileData = true, full, 70, emptySet()))
        assertEquals("čeká na nabíječku (baterie 20 %)", waitReason(queued, onWifi = true, mobileData = false, low, 70, emptySet()))
        assertEquals("účet je potřeba znovu přihlásit", waitReason(queued, true, false, full, 70, setOf("me@gmail.com")))
        assertEquals("1 ve frontě · čeká na Wi-Fi", queueStatus(1, 0, null, "čeká na Wi-Fi"))
        assertEquals("1 ve frontě · odešle se za chvíli", queueStatus(1, 0, null, null))
    }
}

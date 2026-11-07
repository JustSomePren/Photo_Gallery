package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 18: what happens to a pending "Odeslat hned" when the queue changes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass18QueueTest {
    private val setup = AppSetupRule { prefs -> prefs.addAccount("me@gmail.com") }
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

    private fun pending(): WorkInfo = WorkManager.getInstance(setup.app).getWorkInfosForUniqueWork("upload-once").get()
        .single { !it.state.isFinished }

    /** Control: "Odeslat hned" waits for any network, not just Wi-Fi. */
    @Test
    fun sendNowWaitsForAnyNetwork() {
        start()
        val camera = photos.filter { it.isCameraShot }
        vm.enqueue(camera.take(2), "me@gmail.com", null); idleMain()
        vm.sendNow(); idleMain()
        assertEquals(NetworkType.CONNECTED, pending().constraints.requiredNetworkType)
    }

    /**
     * On a trip without signal the user taps "Odeslat hned" (send over mobile data as soon as there is any
     * network), then adds one more photo to the queue. The pending "send now" run is silently replaced by an
     * ordinary Wi-Fi-only run, so nothing is sent when mobile data comes back.
     */
    @Test
    fun addingPhotoKeepsPendingSendNow() {
        start()
        val camera = photos.filter { it.isCameraShot }
        vm.enqueue(camera.take(2), "me@gmail.com", null); idleMain()
        vm.sendNow(); idleMain()
        vm.enqueue(camera.drop(2).take(1), "me@gmail.com", null); idleMain()
        assertEquals("send now was dropped", NetworkType.CONNECTED, pending().constraints.requiredNetworkType)
    }
}

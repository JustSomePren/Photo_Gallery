package com.justsomepren.gallery.walkthrough

import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.data.MediaItem
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 23: a big library, "Vybrat vše" and leaving the app. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass23WalkthroughTest {
    private val setup = AppSetupRule()
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]

    private fun start(media: List<MediaItem>) {
        vm.loadMedia = { media }
        vm.refresh()
        compose.waitUntil(60_000) { idleMain(); vm.media.value == media }
        compose.waitForIdle()
    }

    private fun savedStateBytes(): Int {
        var size = 0
        compose.activityRule.scenario.onActivity { activity ->
            val bundle = Bundle()
            InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(activity, bundle)
            val parcel = Parcel.obtain()
            parcel.writeBundle(bundle)
            size = parcel.dataSize()
            parcel.recycle()
        }
        return size
    }

    /**
     * A phone with 80 000 photos (years of Pixel pictures). The user long-presses a photo and taps "Vybrat vše"
     * to queue everything. The selection is saved in the activity's instance state as one Long per photo
     * (rememberSaveable), so pressing Home or rotating the phone writes ~640 KB into the saved state Bundle;
     * Android sends that through Binder (1 MB limit shared with everything else) and kills the app with
     * TransactionTooLargeException once a library is big enough.
     */
    @Test
    fun selectAllInBigLibraryKeepsSavedStateSmall() {
        val base = Fixtures.photos.first()
        val media = (0 until 80_000).map { i ->
            base.copy(id = i.toLong(), name = "PXL_$i.jpg", date = base.date - i * 60_000L, dateTaken = base.date - i * 60_000L)
        }
        start(media)
        // Long press the first photo cell (below the status strip and month header).
        compose.onNodeWithContentDescription("Hledat v Google Fotkách").assertExists()
        compose.onRoot().performTouchInput { longClick(androidx.compose.ui.geometry.Offset(60f, 700f)) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Vybrat vše").performClick()
        compose.waitForIdle()
        assertTrue(vm.media.value!!.size == 80_000)
        val bytes = savedStateBytes()
        println("saved state after select all: $bytes bytes")
        assertTrue("saved state is $bytes bytes", bytes < 200_000)
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onRoot() = onNode(androidx.compose.ui.test.isRoot())
}

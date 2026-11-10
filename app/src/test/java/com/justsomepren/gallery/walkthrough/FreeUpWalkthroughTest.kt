package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
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
import org.robolectric.shadows.ShadowToast

/** Uvolnit místo: the user picks from verified photos, confirms, and nothing goes without a fresh check. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class FreeUpWalkthroughTest {
    private val setup = AppSetupRule()
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]

    @Test
    fun pickConfirmAndNothingGoesWhenGoogleCannotBeAsked() {
        vm.loadMedia = { photos }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value?.isNotEmpty() == true }
        val verified = photos.filter { it.isCameraShot }.take(6)
        // As if the check had confirmed these six in Google Photos.
        vm.freeUp.value = GalleryViewModel.FreeUp(verified, verified.sumOf { it.size }, mapOf("me@gmail.com" to 6), emptyList(), 0)

        compose.onNodeWithText("Nastavení").performClick()
        compose.onNodeWithText("Vybrat, co smazat z telefonu").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Vybráno 6 z 6", substring = true).assertExists()

        // Untick one.
        compose.onNodeWithText("Zrušit výběr").performClick()
        compose.onNodeWithText("Vybrat vše").performClick()
        assertEquals(6, vm.selection("free-up").value.size)
        vm.selection("free-up").value = verified.drop(1).map { it.id }.toSet()
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/walkthrough/free_up_pick.png")

        compose.onNodeWithText("Smazat vybrané (5)").performClick()
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/walkthrough/free_up_confirm.png")
        compose.onNodeWithText("Smazat 5 z telefonu?").assertExists()

        // Google cannot be reached here: nothing is deleted.
        compose.onNodeWithText("Smazat").performClick()
        compose.waitUntil(15_000) { idleMain(); ShadowToast.getLatestToast() != null }
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().contains("nic se nesmazalo"))
    }
}

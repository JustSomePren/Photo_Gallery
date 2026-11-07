package com.justsomepren.gallery.walkthrough

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import com.justsomepren.gallery.walkthrough.Fixtures.trip
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 6: screens no earlier pass looked at (partial photo access, search, new share target). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass6ScreensTest {
    private val setup = AppSetupRule { prefs ->
        prefs.addAccount("shared@gmail.com")
        prefs.saveEvent(trip)
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        // "Allow selected photos only".
        shadowOf(app).denyPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        shadowOf(app).grantPermissions(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.cloud"
                name = "com.example.cloud.Upload"
                applicationInfo = ApplicationInfo().apply { packageName = "com.example.cloud" }
                nonLocalizedLabel = "Cloud"
            }
            nonLocalizedLabel = "Cloud"
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*"), info)
    }
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]

    private fun start() {
        vm.loadMedia = { photos }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value?.size == photos.size }
        compose.waitForIdle()
    }

    private fun settle() {
        repeat(6) { compose.mainClock.advanceTimeBy(200); idleMain() }
    }

    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    @Test
    fun partialAccessSettings() {
        start()
        shot("p6_10_grid_partial_access")
        compose.onNodeWithText("Nastavení").performClick(); compose.waitForIdle()
        shot("p6_11_settings_partial_access")
        compose.onNodeWithText("Změnit přístup k fotkám").assertIsDisplayed()
    }

    @Test
    fun searchDialog() {
        start()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Hledat v Google Fotkách").performClick()
        settle()
        shot("p6_12_search_dialog")
        compose.onNodeWithText("Hledat").assertExists()
    }

    @Test
    fun addShareTargetDialog() {
        start()
        compose.onNodeWithText("Odesílání").performClick(); compose.waitForIdle()
        // The "+" next to "Poslat ručně přes aplikaci" is the third "Přidat" button.
        val add = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Přidat"))[2]
        add.performScrollTo(); compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        add.performClick()
        settle()
        shot("p6_13_add_target_dialog")
        compose.onNodeWithText("Cloud", substring = true).assertExists()
    }
}

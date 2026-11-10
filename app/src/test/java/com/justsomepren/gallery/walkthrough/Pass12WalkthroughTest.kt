package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import com.justsomepren.gallery.walkthrough.Fixtures.trip
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 12: typed album names, calendar layout, editor date picker. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass12WalkthroughTest {
    private val setup = AppSetupRule { prefs ->
        prefs.addAccount("shared@gmail.com")
        prefs.addAccount("me@gmail.com")
    }
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]
    private val prefs get() = vm.prefs

    private fun start(media: List<MediaItem> = photos) {
        vm.loadMedia = { media }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value?.size == media.size }
        compose.waitForIdle()
    }

    private fun settle() {
        repeat(6) { compose.mainClock.advanceTimeBy(200); idleMain() }
    }

    private fun photoNode(i: Int) = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))[i]
    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    /**
     * The user selects a photo, taps "Poslat do cloudu" and types the album "Rodina"; the keyboard's word
     * suggestion leaves "Rodina " with a trailing space. The event editor trims album names, the send dialog
     * does not: the queue item goes to album "Rodina " and the worker creates a second Google Photos album
     * next to "Rodina" (album ids are stored per exact title).
     */
    @Test
    fun typedAlbumInSendDialogIsTrimmed() {
        prefs.saveEvent(trip)
        start()
        // Typing into a focused field never lets Robolectric idle; this is the exact call the account row makes
        // (Actions.kt:480) with the text field's raw value.
        vm.enqueue(listOf(photos[40]), "shared@gmail.com", "Dovca Praha ")
        val queued = prefs.queue.value.single { it.key == photos[40].key }
        assertEquals("same album as the trip event", trip.album, queued.album)
    }

    /** Calendar with an event crossing a week boundary and a month needing six rows. */
    @Test
    fun calendarWeekCrossingEvent() {
        prefs.saveEvent(trip.copy(autoSend = false, account = null, album = null))
        prefs.saveEvent(Fixtures.birthday)
        start()
        prefs.setCalendarView(true)
        compose.waitForIdle()
        shot("p12_02_calendar")
    }
}

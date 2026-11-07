package com.justsomepren.gallery.walkthrough

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.Prefs
import com.justsomepren.gallery.data.QueueItem
import com.justsomepren.gallery.data.Source
import com.justsomepren.gallery.data.day
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import com.justsomepren.gallery.walkthrough.Fixtures.today
import com.justsomepren.gallery.walkthrough.Fixtures.trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 4: editor round trips, dialogs across rotation, navigation chains, process death. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass4WalkthroughTest {
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

    private fun tripPhotos() = photos.filter { it.isCameraShot && trip.covers(it.day) }

    @Test
    fun savingEventWithoutAlbumKeepsItWithoutAlbum() {
        // Auto-send without an album: the user cleared the album field earlier.
        prefs.saveEvent(trip.copy(album = null))
        start()
        assertEquals(tripPhotos().size, prefs.queue.value.size)
        assertTrue(prefs.queue.value.all { it.album == null })

        compose.mainClock.autoAdvance = false
        vm.editingEvent = prefs.events.value.single()
        settle()
        shot("p4_01_editor_event_without_album")
        // Only "Uložit", nothing changed.
        compose.onNodeWithText("Uložit").performClick()
        settle()
        assertNull(prefs.events.value.single().album)
        assertTrue(prefs.queue.value.all { it.album == null })
    }

    @Test
    fun calendarDayGridViewerBackChain() {
        prefs.setCalendarView(true)
        start()
        val day = today.minusDays(2)
        compose.onAllNodesWithText("${day.dayOfMonth}")[0].performClick(); compose.waitForIdle()
        val top = vm.stack.last() as Screen.Grid
        assertEquals(day.toEpochDay(), (top.source as Source.Period).start)
        shot("p4_03_calendar_day_grid")
        photoNode(1).performClick(); compose.waitForIdle()
        assertTrue(vm.stack.last() is Screen.Viewer)
        shot("p4_04_calendar_day_viewer")
        compose.onNodeWithContentDescription("Zpět").performClick(); compose.waitForIdle()
        assertTrue(vm.stack.last() is Screen.Grid)
        compose.onNodeWithContentDescription("Zpět").performClick(); compose.waitForIdle()
        assertTrue(vm.stack.isEmpty())
        compose.onNodeWithContentDescription("Mřížka").assertIsDisplayed()
    }

    @Test
    fun queueFailedDismissedAndEventsSurviveProcessDeath() {
        prefs.saveEvent(trip)
        start()
        val camera = photos.filter { it.isCameraShot && !trip.covers(it.day) }.take(3)
        prefs.enqueue(camera.map { QueueItem.of(it, "me@gmail.com", "Rodina") })
        prefs.markFailed(prefs.queue.value.filter { it.key == camera[0].key })
        prefs.markSent(prefs.queue.value.filter { it.key == camera[1].key })
        prefs.clearQueue()
        val failed = prefs.failed.value

        Prefs.reset()
        val reborn = Prefs.get(setup.app)
        assertEquals(failed, reborn.failed.value)
        assertEquals(prefs.events.value, reborn.events.value)
        assertTrue(reborn.queue.value.isEmpty())
        // Neither the cleared, the failed nor the sent photos come back on the next refresh.
        assertEquals(0, reborn.collectAuto(photos))
        assertEquals(0, reborn.enqueue(camera.drop(1).map { QueueItem.of(it, "me@gmail.com", "Rodina") }.take(1)))
    }

    @Test
    fun eventPhotosSelectAllSelectsWholeEvent() {
        prefs.saveEvent(trip.copy(autoSend = false, account = null, album = null))
        start()
        vm.stack.add(Screen.EventPhotos(trip.id)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Vybrat celé album").performClick(); compose.waitForIdle()
        shot("p4_05_event_photos_all_selected")
        val expected = photos.count { trip.covers(it.day) }
        assertTrue(expected > 0)
        compose.onNodeWithText("$expected").assertIsDisplayed()
    }
}

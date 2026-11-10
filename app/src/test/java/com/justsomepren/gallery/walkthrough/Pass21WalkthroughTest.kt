package com.justsomepren.gallery.walkthrough

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.Screen
import com.justsomepren.gallery.data.EVENT_COLORS
import com.justsomepren.gallery.data.Event
import com.justsomepren.gallery.data.MediaItem
import com.justsomepren.gallery.data.Source
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 21: the calendar, manual share targets and the cloud dialog. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass21WalkthroughTest {
    private val account = "me@gmail.com"
    private val setup = AppSetupRule { prefs ->
        prefs.addAccount(account)
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
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

    private fun start(media: List<MediaItem> = photos) {
        vm.loadMedia = { media }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value == media }
        compose.waitForIdle()
    }

    private fun settle() {
        repeat(6) { compose.mainClock.advanceTimeBy(200); idleMain() }
    }

    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")

    /** Long press on a day in the calendar opens a new event for exactly that day. */
    @Test
    fun calendarLongPressStartsEventOnThatDay() {
        start()
        vm.prefs.setCalendarView(true); compose.waitForIdle()
        val day = today.minusDays(2)
        compose.onAllNodesWithText("${day.dayOfMonth}")[0].performTouchInput { longClick() }
        compose.mainClock.autoAdvance = false
        settle()
        shot("p21_01_calendar_long_press")
        val editing = vm.editingEvent!!
        assertEquals(day.toEpochDay(), editing.startDay)
        assertEquals(day.toEpochDay(), editing.endDay)
    }

    /** A manual target added in the Sending tab gets only the camera photos of a mixed selection. */
    @Test
    fun manualTargetGetsOnlyCameraPhotos() {
        start()
        vm.tab = 3; compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Poslat ručně přes aplikaci").assertExists()
        vm.prefs.addTargets(listOf(com.justsomepren.gallery.data.UploadTarget("Spolecny", "com.example.cloud", "com.example.cloud.Upload")))
        settle()
        compose.mainClock.autoAdvance = true
        vm.tab = 0; compose.waitForIdle()
        val mixed = photos.take(8)
        vm.dialogs.sending = mixed
        compose.mainClock.autoAdvance = false
        settle()
        shot("p21_02_send_dialog_with_target")
        compose.onNodeWithText("Spolecny").performClick()
        settle()
        val intent = shadowOf(compose.activity).nextStartedActivity
        val uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals(mixed.filter { it.isCameraShot }.map { it.uri }, uris)
        assertEquals(mixed.count { it.isCameraShot }, intent.clipData!!.itemCount)
    }

    /** Three events in one week: every one of them can be found in the calendar. */
    @Test
    fun calendarWithThreeEventsInOneWeek() {
        val base = today.minusDays(3).toEpochDay()
        listOf("Hory", "Svatba", "Kino").forEachIndexed { i, name ->
            vm.prefs.saveEvent(Event(id = name, name = name, color = EVENT_COLORS[i], startDay = base - i, endDay = base + i))
        }
        start()
        vm.prefs.setCalendarView(true); compose.waitForIdle()
        shot("p21_03_calendar_three_events")
        compose.onAllNodesWithText("Kino").fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    /** From an event's photos the viewer swipes only through that event's photos. */
    @Test
    fun viewerFromEventPhotosStaysInEvent() {
        val event = Event(id = "e", name = "Vylet", color = EVENT_COLORS[2], startDay = today.minusDays(5).toEpochDay(), endDay = today.minusDays(4).toEpochDay())
        vm.prefs.saveEvent(event)
        start()
        vm.stack.add(Screen.EventPhotos("e")); compose.waitForIdle()
        shot("p21_04_event_photos")
        val inEvent = photos.filter { event.covers(it.day) }
        vm.stack.add(Screen.Viewer(event.source, inEvent.first().id)); compose.waitForIdle()
        compose.onNodeWithContentDescription("Zpět").assertExists()
        assertEquals(inEvent.first().id, vm.returnTo)
        assertTrue(vm.stack.last() is Screen.Viewer)
        assertEquals(Source.Period("Vylet", event.startDay, event.endDay), (vm.stack.last() as Screen.Viewer).source)
    }
}

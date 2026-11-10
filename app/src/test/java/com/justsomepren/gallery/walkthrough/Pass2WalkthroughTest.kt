package com.justsomepren.gallery.walkthrough

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.work.NetworkType
import androidx.work.WorkManager
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.justsomepren.gallery.GalleryViewModel
import com.justsomepren.gallery.MainActivity
import com.justsomepren.gallery.data.QueueItem
import com.justsomepren.gallery.data.day
import com.justsomepren.gallery.walkthrough.Fixtures.photos
import com.justsomepren.gallery.walkthrough.Fixtures.trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Bug-hunt pass 2: the user flows around the queue, accounts and events, driven through the UI. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class Pass2WalkthroughTest {
    private val setup = AppSetupRule { prefs ->
        prefs.addAccount("shared@gmail.com")
        prefs.addAccount("me@gmail.com")
        prefs.saveEvent(Fixtures.birthday)
    }
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(setup).around(compose)

    private val vm get() = ViewModelProvider(compose.activity)[GalleryViewModel::class.java]
    private val prefs get() = vm.prefs

    private fun start() {
        vm.loadMedia = { photos }
        vm.refresh()
        compose.waitUntil(30_000) { idleMain(); vm.media.value?.isNotEmpty() == true }
        compose.waitForIdle()
    }

    /** For screens with a focused text field, which never get idle in Robolectric. */
    private fun settle() {
        repeat(6) { compose.mainClock.advanceTimeBy(200); idleMain() }
    }

    private fun photoNode(i: Int) = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))[i]
    private fun shot(name: String) = captureScreenRoboImage("build/outputs/walkthrough/$name.png")
    private fun openTab(name: String) {
        compose.onAllNodesWithText(name).onFirst().performClick()
        compose.waitForIdle()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirst() = this[0]

    @Test
    fun sendDialogQueuesOnlyCameraShots() {
        start()
        // photos[0] is a screenshot, 1..3 are from the camera.
        photoNode(0).performTouchInput { longClick() }
        (1..3).forEach { photoNode(it).performClick() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("Poslat do cloudu").performClick()
        settle()
        shot("p2_01_send_dialog_4")
        compose.onNodeWithText("me@gmail.com").performClick()
        settle()
        assertEquals(3, prefs.queue.value.size)
        assertTrue(prefs.queue.value.all { it.account == "me@gmail.com" && it.album == null })
        assertTrue(prefs.queue.value.none { it.key == photos[0].key })
    }

    @Test
    fun clearQueueInSendingTabSurvivesRecreateAndRefresh() {
        prefs.saveEvent(trip)
        start()
        assertTrue(prefs.queue.value.isNotEmpty())
        openTab("Odesílání")
        shot("p2_02_sending_with_queue")
        compose.onNodeWithText("Vyprázdnit").performClick()
        compose.waitForIdle()
        assertEquals(0, prefs.queue.value.size)
        compose.activityRule.scenario.recreate()
        start()
        assertEquals(0, prefs.queue.value.size)
        shot("p2_03_sending_after_clear_recreate")
        compose.onNodeWithText("Vše odesláno").assertIsDisplayed()
    }

    @Test
    fun removingAccountInUiDropsItsQueueAndEvents() {
        prefs.saveEvent(trip)
        start()
        assertTrue(prefs.queue.value.any { it.account == "shared@gmail.com" })
        openTab("Odesílání")
        compose.onAllNodesWithContentDescription("Odebrat")[0].performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("me@gmail.com"), prefs.accounts.value)
        assertTrue(prefs.queue.value.none { it.account == "shared@gmail.com" })
        vm.refresh(); compose.waitForIdle(); idleMain()
        assertTrue(prefs.queue.value.none { it.account == "shared@gmail.com" })
        shot("p2_04_sending_after_account_removed")
    }

    @Test
    fun retryAfterRemovingAccountDoesNotQueueForIt() {
        start()
        val camera = photos.filter { it.isCameraShot }.take(3)
        prefs.enqueue(camera.map { QueueItem.of(it, "shared@gmail.com", null) })
        prefs.markFailed(prefs.queue.value)
        openTab("Odesílání")
        compose.onAllNodesWithContentDescription("Odebrat")[0].performScrollTo().performClick()
        compose.waitForIdle()
        shot("p2_05_failed_left_after_account_removed")
        // Its failed photos go with the account, so there is nothing to retry for it.
        assertTrue(prefs.failed.value.isEmpty())
        compose.onNodeWithText("Zkusit znovu").assertDoesNotExist()
        prefs.retryFailed()
        assertTrue(prefs.queue.value.none { it.account == "shared@gmail.com" })
    }

    // The editor's text fields keep Robolectric from idling, so "Uložit" is done with the same call the button makes.
    @Test
    fun turningAutoSendOffStopsSendingEventPhotos() {
        prefs.saveEvent(trip)
        start()
        assertTrue(prefs.queue.value.isNotEmpty())
        vm.saveEvent(prefs.events.value.first { it.id == trip.id }.copy(autoSend = false, account = null, album = null))
        assertEquals(0, prefs.queue.value.count { q -> photos.any { it.key == q.key && trip.covers(it.day) } })
    }

    @Test
    fun deletingAutoSendEventStopsSendingItsPhotos() {
        prefs.saveEvent(trip)
        start()
        assertTrue(prefs.queue.value.isNotEmpty())
        vm.deleteEvent(prefs.events.value.first { it.id == trip.id })
        assertEquals(0, prefs.queue.value.size)
    }

    @Test
    fun editorScreenshotsAndNewAutoSendEventQueuesCameraPhotos() {
        prefs.saveEvent(trip)
        start()
        compose.mainClock.autoAdvance = false
        vm.editingEvent = prefs.events.value.first { it.id == trip.id }
        settle()
        shot("p2_06_editor_existing")
        vm.editingEvent = null
        vm.newEvent(Fixtures.today.minusDays(20).toEpochDay())
        settle()
        shot("p2_07_editor_new_from_day")
        val draft = vm.editingEvent!!
        vm.editingEvent = null
        settle()
        val before = prefs.queue.value.size
        vm.saveEvent(draft.copy(name = "Hory", endDay = draft.startDay + 6, autoSend = true, account = "me@gmail.com", album = "Hory"))
        val saved = prefs.events.value.single { it.name == "Hory" }
        val expected = photos.count { it.isCameraShot && saved.covers(it.day) }
        assertTrue(expected > 0)
        assertEquals(before + expected, prefs.queue.value.size)
        assertTrue(prefs.queue.value.filter { it.account == "me@gmail.com" }.all { it.album == "Hory" })
    }

    @Test
    fun mobileDataSwitchUpdatesPendingUpload() {
        start()
        val work = WorkManager.getInstance(setup.app)
        vm.enqueue(photos.filter { it.isCameraShot }.take(2), "me@gmail.com", null)
        idleMain()
        fun network() = work.getWorkInfosForUniqueWork("upload-once").get()
            .single { !it.state.isFinished }.constraints.requiredNetworkType
        assertEquals(NetworkType.UNMETERED, network())
        vm.setMobileData(true)
        idleMain()
        assertEquals(NetworkType.CONNECTED, network())
    }

    @Test
    fun calendarChoiceAndTabSurviveRecreate() {
        start()
        compose.onNodeWithContentDescription("Kalendář").performClick()
        compose.waitForIdle()
        openTab("Alba")
        compose.activityRule.scenario.recreate()
        start()
        assertEquals(2, vm.tab)
        openTab("Fotky")
        compose.onNodeWithContentDescription("Mřížka").assertIsDisplayed()
        shot("p2_08_calendar_after_recreate")
    }
    @Test
    fun viewerVideoMoveDialogAndCalendarLongPress() {
        start()
        // photos[5] is a video.
        vm.stack.add(com.justsomepren.gallery.Screen.Viewer(com.justsomepren.gallery.data.Source.All, photos[5].id))
        compose.waitForIdle()
        shot("p2_09_viewer_video")
        compose.onNodeWithContentDescription("Přehrát").assertIsDisplayed()
        compose.onNodeWithContentDescription("Další").performClick(); compose.waitForIdle()
        compose.onNodeWithText("Přesunout do složky").performClick(); compose.waitForIdle()
        shot("p2_10_move_dialog")
        // Moving into the camera folder would make any picture look like a camera photo.
        compose.onNodeWithText("DCIM/Camera").assertDoesNotExist()
        compose.onNodeWithText("Zrušit").performClick(); compose.waitForIdle()
        compose.onNodeWithContentDescription("Zpět").performClick(); compose.waitForIdle()
        assertTrue(vm.stack.isEmpty())

        compose.onNodeWithContentDescription("Kalendář").performClick(); compose.waitForIdle()
        shot("p2_11_calendar")
        compose.onAllNodesWithText("${Fixtures.today.minusDays(1).dayOfMonth}")[0].performTouchInput { longClick() }
        compose.mainClock.autoAdvance = false
        settle()
        shot("p2_12_calendar_longpress_editor")
        assertEquals(Fixtures.today.minusDays(1).toEpochDay(), vm.editingEvent?.startDay)
    }
}

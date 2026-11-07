package com.justsomepren.gallery.walkthrough

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.justsomepren.gallery.data.Prefs
import com.justsomepren.gallery.data.QueueItem
import com.justsomepren.gallery.data.UploadWorker
import kotlinx.coroutines.sync.Mutex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Bug-hunt pass 22: photos queued while an upload run is going. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Pass22WorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val account = "me@gmail.com"
    private lateinit var prefs: Prefs
    private lateinit var work: WorkManager

    /** The worker's own "only one run sends at a time" lock; holding it keeps a started run RUNNING. */
    private val sending: Mutex = UploadWorker::class.java.getDeclaredField("sending").apply { isAccessible = true }.get(null) as Mutex

    @Before
    fun setUp() {
        onWifi(context)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        work = WorkManager.getInstance(context)
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "sent.txt").delete()
        Prefs.reset()
        prefs = Prefs.get(context)
        prefs.addAccount(account)
    }

    @After
    fun tearDown() {
        if (sending.isLocked) sending.unlock()
        prefs.uploading.value = null
    }

    private fun item(n: Int) =
        QueueItem("PXL_$n.jpg|$n", Uri.parse("content://media/external/images/media/$n").toString(), "PXL_$n.jpg", "image/jpeg", account, null)

    private fun once(): List<WorkInfo> = work.getWorkInfosForUniqueWork("upload-once").get()

    private fun pendingUploads(): List<WorkInfo> =
        listOf("upload-once", "upload-next", "upload-wifi", "upload-charging", "upload-periodic")
            .flatMap { work.getWorkInfosForUniqueWork(it).get() }
            .filter { !it.state.isFinished }

    /** Starts the queued run and keeps it RUNNING, like a run in the middle of a long upload. */
    private fun startRunAndHold() {
        assertTrue(sending.tryLock())
        val id = once().single { !it.state.isFinished }.id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(id)
        assertEquals(WorkInfo.State.RUNNING, work.getWorkInfoById(id).get()!!.state)
        prefs.uploading.value = 0 to 1
    }

    /** Control: queueing with nothing running leaves a run waiting for Wi-Fi. */
    @Test
    fun queueingWhenIdleSchedulesRun() {
        prefs.enqueue(listOf(item(1)))
        UploadWorker.schedule(context, restart = prefs.uploading.value == null)
        assertEquals(1, pendingUploads().size)
    }

    /**
     * A long hand-picked queue is going up at home (the one-time run is RUNNING). The user takes a new photo
     * and sends it to the cloud from the app. sendSoon() sees an upload in progress and enqueues the one-time
     * run with KEEP, which WorkManager drops because the unique work is still RUNNING. The running run sends only
     * photos it saw at its start, so it ends with success; the new photo stays in the queue ("1 čeká · odešle se
     * na Wi-Fi") with no run scheduled for it until the app process is restarted or settings are touched.
     * (In Robolectric MediaStore is empty, so the run sends nothing; what matters is that nothing is left pending.)
     */
    @Test
    fun photoQueuedDuringRunIsSentAfterIt() {
        prefs.enqueue(listOf(item(1)))
        UploadWorker.schedule(context, restart = true)
        startRunAndHold()

        // GalleryViewModel.enqueue -> sendSoon(): restart only when nothing is uploading.
        prefs.enqueue(listOf(item(2)))
        UploadWorker.schedule(context, restart = prefs.uploading.value == null)

        // The running run finishes.
        prefs.uploading.value = null
        sending.unlock()
        val deadline = System.currentTimeMillis() + 10_000
        while (once().any { it.state == WorkInfo.State.RUNNING } && System.currentTimeMillis() < deadline) Thread.sleep(20)

        assertEquals(2, prefs.queue.value.size)
        assertTrue("queued photo has no upload run left", pendingUploads().isNotEmpty())
    }
}

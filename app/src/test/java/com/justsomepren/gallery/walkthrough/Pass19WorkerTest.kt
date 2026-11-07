package com.justsomepren.gallery.walkthrough

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.justsomepren.gallery.data.Prefs
import com.justsomepren.gallery.data.QueueItem
import com.justsomepren.gallery.data.UploadWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Bug-hunt pass 19: a "send now" run that finds nothing to send. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Pass19WorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val account = "me@gmail.com"
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        runCatching { WorkManager.initialize(context, Configuration.Builder().build()) }
        context.getSharedPreferences("gallery", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "sent.txt").delete()
        Prefs.reset()
        prefs = Prefs.get(context)
        prefs.addAccount(account)
    }

    private fun item(n: Int) =
        QueueItem("PXL_$n.jpg|$n", Uri.parse("content://media/external/images/media/$n").toString(), "PXL_$n.jpg", "image/jpeg", account, null)

    private fun runSendNow(): ListenableWorker.Result =
        TestListenableWorkerBuilder<UploadWorker>(context).setInputData(workDataOf("now" to true)).build().startWork().get()

    private fun pendingNetwork(): NetworkType = WorkManager.getInstance(context).getWorkInfosForUniqueWork("upload-once").get()
        .single { !it.state.isFinished }.constraints.requiredNetworkType

    /** Control: "Odeslat hned" is pending while its run has not happened yet. */
    @Test
    fun sendNowIsPendingUntilItsRun() {
        prefs.enqueue(listOf(item(1)))
        UploadWorker.sendNow(context)
        assertTrue(prefs.sendNowPending)
        assertEquals(NetworkType.CONNECTED, pendingNetwork())
    }

    /**
     * Without signal the user taps "Odeslat hned", then "Vyprázdnit". The send-now run later finds an empty
     * queue and returns early without clearing the flag. From then on every ordinary queueing (and every
     * restart after a settings change) is scheduled as a "send now": over mobile data and ignoring the battery
     * threshold, although "Posílat i přes mobilní data" is off. The flag is saved, so it survives restarts.
     */
    @Test
    fun sendNowRunWithEmptyQueueEndsSendNow() {
        prefs.enqueue(listOf(item(1)))
        UploadWorker.sendNow(context)
        prefs.clearQueue()
        assertEquals(ListenableWorker.Result.success(), runSendNow())
        assertEquals("send now still pending after its run", false, prefs.sendNowPending)

        // Days later, at home: a photo queued by hand must wait for Wi-Fi.
        prefs.enqueue(listOf(item(2)))
        UploadWorker.schedule(context, restart = true)
        assertEquals(NetworkType.UNMETERED, pendingNetwork())
    }
}

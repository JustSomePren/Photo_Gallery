package com.justsomepren.gallery.data

import android.content.Context
import android.util.Size
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Asks the system to prepare thumbnails of the newest photos, but only while charging.
 * Browsing on battery then just reads ready-made thumbnails.
 */
class WarmupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val size = applicationContext.resources.displayMetrics.widthPixels / 4
        for (item in MediaRepository(applicationContext).loadAll().take(LIMIT)) {
            if (isStopped) break
            runCatching {
                applicationContext.contentResolver.loadThumbnail(item.uri, Size(size, size), null).recycle()
            }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "thumbnail-warmup"
        private const val LIMIT = 5000

        fun schedule(context: Context, enabled: Boolean) {
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<WarmupWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

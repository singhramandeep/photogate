package com.raman.photogate

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * When PhotoGate checks the watched folders, without a permanent notification:
 *  - Immediately: Android wakes PhotoGate when the photo library changes (a content-trigger job); each new photo
 *    gets its own Upload / Bin / Delete now notification.
 *  - Every 1, 2, 4 or 8 hours: a periodic check; if anything new arrived, one "N new photos to review" notification.
 */
object Scheduler {
    const val INSTANT = "check-instant"
    const val PERIODIC = "check-periodic"

    /** Frequency choices in minutes; 0 = immediately. */
    val CHOICES = listOf(0 to "Immediately", 60 to "Every hour", 120 to "Every 2 h", 240 to "Every 4 h", 480 to "Every 8 h")

    fun label(min: Int): String = CHOICES.firstOrNull { it.first == min }?.second ?: "Every ${min / 60} h"

    /** Checking runs while access is granted and at least one folder is switched on. */
    fun active(c: Context): Boolean = Gallery.hasAccess() && Store.folders(c).isNotEmpty()

    /** Puts the right background job in place for the current settings (safe to call often). */
    fun apply(c: Context) {
        val wm = WorkManager.getInstance(c.applicationContext)
        if (!active(c)) {
            wm.cancelUniqueWork(INSTANT)
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val min = Store.checkFreqMin(c)
        if (min == 0) {
            wm.cancelUniqueWork(PERIODIC)
            armInstant(c, replace = false)
        } else {
            wm.cancelUniqueWork(INSTANT)
            val req = PeriodicWorkRequestBuilder<CheckWorker>(min.toLong(), TimeUnit.MINUTES)
                .setInputData(workDataOf("periodic" to true))
                .build()
            // UPDATE keeps the schedule when nothing changed, and switches to the new interval when it did
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    /** One-shot job that runs when the photo library changes. Re-armed after every run. */
    fun armInstant(c: Context, replace: Boolean) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(1, TimeUnit.SECONDS) // the camera writes a shot in several steps
            .setTriggerContentMaxDelay(5, TimeUnit.SECONDS)
            .build()
        val req = OneTimeWorkRequestBuilder<CheckWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(c.applicationContext).enqueueUniqueWork(
            INSTANT, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req,
        )
    }
}

class CheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val periodic = inputData.getBoolean("periodic", false)
        var added = 0
        // scan, and again a little later while a file is still being written (max ~20 s)
        repeat(6) {
            val r = Processor.scanSafely(c, if (periodic) "Scheduled check" else "change", perPhotoNotify = !periodic)
            added += r.added
            if (!r.again) return@repeat
            delay(Processor.SETTLE_MS + 500)
        }
        if (periodic && added > 0) Notifier.postSummary(c, Store.staged(c).count { !it.discarded })
        runCatching { Gallery.sweep(c) }
        if (!periodic && Store.checkFreqMin(c) == 0 && Scheduler.active(c)) Scheduler.armInstant(c, replace = true)
        return Result.success()
    }
}

package com.raman.photogate

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What PhotoGate does with files: stage, approve, discard, delete. Android side of [Gate]. */
object Gallery {
    /** RELATIVE_PATH of the approved folder. Turn on backup for this folder in Google Photos. */
    const val FOLDER = "DCIM/PhotoGate/"

    fun gate(): Gate = Gate(File(Environment.getExternalStorageDirectory(), FOLDER.trimEnd('/')))

    /** Moving other apps' photos needs "All files access" (a one-time switch in system settings). */
    fun hasAccess(): Boolean = Environment.isExternalStorageManager()

    fun accessIntent(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${c.packageName}"))

    /** Tell MediaStore (and so Google Photos and galleries) that these paths changed. */
    private fun rescan(c: Context, vararg paths: String) {
        MediaScannerConnection.scanFile(c.applicationContext, arrayOf(*paths), null, null)
    }

    /** A new photo leaves its folder and waits in staging. Null if it could not be moved. */
    fun stage(c: Context, item: Item): Staged? {
        val src = item.file ?: return null
        return try {
            val dst = gate().stage(src)
            rescan(c, src.path, dst.path)
            Staged(item.id, dst.path, dst.name, item.folder, item.isVideo, System.currentTimeMillis())
                .also { Store.addStaged(c, it) }
        } catch (e: IOException) {
            null
        }
    }

    /** "Upload everything" mode: straight into PhotoGate, no staging. */
    fun approveDirect(c: Context, item: Item): Boolean {
        val src = item.file ?: return false
        return try {
            val dst = gate().approve(src)
            rescan(c, src.path, dst.path)
            Store.countApproved(c)
            true
        } catch (e: IOException) {
            false
        }
    }

    fun approve(c: Context, id: Long): Boolean {
        val s = Store.stagedById(c, id) ?: return false
        Notifier.cancel(c, id)
        return try {
            val dst = gate().approve(File(s.path))
            Store.removeStaged(c, id)
            rescan(c, dst.path)
            Store.countApproved(c)
            true
        } catch (e: IOException) {
            if (!File(s.path).exists()) Store.removeStaged(c, id) // gone already, forget it
            false
        }
    }

    /** Keeps the photo in staging until the delete delay is over (0 = delete right away). */
    fun discard(c: Context, id: Long) {
        val s = Store.stagedById(c, id) ?: return
        Notifier.cancel(c, id)
        val delayMs = Store.deleteAfterMin(c) * 60_000L
        if (delayMs <= 0) {
            deleteNow(c, id)
            return
        }
        Store.updateStaged(c, s.copy(deleteAt = System.currentTimeMillis() + delayMs))
        scheduleSweep(c)
    }

    fun deleteNow(c: Context, id: Long) {
        val s = Store.stagedById(c, id) ?: return
        Notifier.cancel(c, id)
        if (gate().delete(File(s.path))) {
            Store.removeStaged(c, id)
            Store.countDeleted(c)
        }
    }

    /** Deletes discarded photos whose time is up and schedules the next run. */
    @Synchronized
    fun sweep(c: Context) {
        val list = Store.staged(c)
        Gate.due(list, System.currentTimeMillis()).forEach { deleteNow(c, it.id) }
        // forget entries whose file vanished (deleted by hand, say)
        list.filter { !File(it.path).exists() }.forEach { Store.removeStaged(c, it.id) }
        scheduleSweep(c)
    }

    fun scheduleSweep(c: Context) {
        val next = Gate.nextDue(Store.staged(c)) ?: return
        val delay = (next - System.currentTimeMillis()).coerceAtLeast(0L)
        val req = OneTimeWorkRequestBuilder<SweepWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(c.applicationContext).enqueueUniqueWork("sweep", ExistingWorkPolicy.REPLACE, req)
    }
}

class SweepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Gallery.sweep(applicationContext)
        return Result.success()
    }
}

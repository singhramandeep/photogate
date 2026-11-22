package com.raman.photogate

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What PhotoGate does with photos, through Android's MediaStore (no "All files access").
 * While waiting, a photo stays in its original folder. Upload moves it into DCIM/PhotoGate/p<category> (synced by the
 * gallery), Bin deletes it after the Bin timer, Delete now deletes it at once.
 *
 * Changing another app's photo needs the user's OK: [Edits] asks Android for it (MediaStore.createWriteRequest /
 * createDeleteRequest). With "Media management" (Android 12+) Android says yes without showing anything.
 * The functions here do the bookkeeping once that OK is in.
 */
object Gallery {
    /** RELATIVE_PATH of the uploaded folder. Sync only its subfolders to Google Photos (Samsung Gallery). */
    const val FOLDER = "DCIM/PhotoGate/"

    private val sdcard: File get() = Environment.getExternalStorageDirectory()

    /** RELATIVE_PATH an uploaded photo of [category] goes to, e.g. "DCIM/PhotoGate/pCamera/". */
    fun targetFolder(category: String): String =
        FOLDER + (Categories.folderName(category)?.let { "$it/" } ?: "")

    // ------------------------------------------------------------------ permissions

    /** Set only by screenshot tests, where the system cannot be asked. */
    var previewAccess: Boolean? = null

    /** Photos and videos permission (full access, not "selected photos only"). */
    fun hasAccess(c: Context): Boolean = previewAccess ?: Media.hasPermission(c)

    /** Android 14+: the user allowed only some photos. PhotoGate cannot see new ones then. */
    fun partialAccess(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 && !Media.hasPermission(c) &&
            ContextCompat.checkSelfPermission(c, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ==
            PackageManager.PERMISSION_GRANTED

    /** "Media management" (Android 12+): move and delete without a confirmation each time. */
    fun canManageMedia(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && runCatching { MediaStore.canManageMedia(c) }.getOrDefault(false)

    fun manageMediaIntent(c: Context): Intent? =
        if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${c.packageName}"))
        else null

    // ------------------------------------------------------------------ MediaStore helpers

    fun uriOf(id: Long, isVideo: Boolean): Uri = ContentUris.withAppendedId(
        if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id,
    )

    /** The MediaStore uri of a tracked photo (old records without a real id are looked up by path). */
    fun uriOf(c: Context, p: Photo): Uri? =
        if (p.id >= 0) uriOf(p.id, p.isVideo) else Media.byPath(c, p.path)?.let { uriOf(it.id, it.isVideo) }

    /**
     * Moves a photo to another folder by changing its RELATIVE_PATH (MediaStore moves the file and keeps its id).
     * Needs write access to [uri]. Returns the new path, or null if it did not work.
     */
    private fun move(c: Context, uri: Uri, relPath: String, name: String): String? {
        val dir = File(sdcard, relPath.trimEnd('/'))
        val finalName = Gate.unique(dir, name).name // never overwrite
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            if (finalName != name) put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
        }
        return try {
            if (c.contentResolver.update(uri, v, null, null) > 0) File(dir, finalName).path else null
        } catch (e: Exception) {
            Store.log(c, "$name: could not move: $e")
            null
        }
    }

    // ------------------------------------------------------------------ tracking

    /** Starts tracking a new photo. Nothing happens to the file. */
    fun track(c: Context, item: Item): Photo? {
        val f = item.file ?: return null
        return Photo(item.id, f.path, f.name, item.folder, item.isVideo, System.currentTimeMillis())
            .also { Store.addStaged(c, it) }
    }

    /** Uris that Upload / Delete now of these Inbox or Bin photos will change. */
    fun stagedUris(c: Context, ids: Collection<Long>): List<Uri> =
        ids.mapNotNull { id -> Store.stagedById(c, id)?.let { uriOf(c, it) } }

    /** Uris that Undo upload / Delete of these uploaded photos will change. */
    fun uploadedUris(c: Context, ids: Collection<Long>): List<Uri> =
        ids.mapNotNull { id -> Store.approvedById(c, id)?.let { uriOf(c, it) } }

    // ------------------------------------------------------------------ actions (after Android's OK)

    /** Upload: move into PhotoGate/p<category> and write the category into the description. Needs write access. */
    fun upload(c: Context, id: Long): Boolean {
        val p = Store.stagedById(c, id) ?: return false
        Notifier.cancel(c, id)
        val uri = uriOf(c, p) ?: run { Store.removeStaged(c, id); return false }
        val cat = Store.categoryOf(c, p.from)
        val path = move(c, uri, targetFolder(cat), p.name) ?: return false
        Describe.write(c, uri, File(path), cat)
        Store.removeStaged(c, id)
        Store.addApproved(
            c, p.copy(path = path, name = File(path).name, seenAt = System.currentTimeMillis(), deleteAt = 0L, category = cat),
        )
        Store.countApproved(c)
        Store.log(c, "${p.name}: uploaded to ${targetFolder(cat)}")
        return true
    }

    /** Undo upload: back to its original folder and into the Inbox. Needs write access. */
    fun undoUpload(c: Context, id: Long): Boolean {
        val a = Store.approvedById(c, id) ?: return false
        val uri = uriOf(c, a) ?: run { Store.removeApproved(c, id); return false }
        val path = move(c, uri, a.from, a.name) ?: return false
        Store.removeApproved(c, id)
        val realId = if (a.id >= 0) a.id else ContentUris.parseId(uri)
        Store.addStaged(c, Photo(realId, path, File(path).name, a.from, a.isVideo, System.currentTimeMillis()))
        Store.log(c, "${a.name}: upload undone, back in ${a.from} and the Inbox")
        return true
    }

    /** Bin: wait for the Bin timer. Returns true if the timer is "Immediately", so the caller deletes right away. */
    fun bin(c: Context, id: Long): Boolean {
        val p = Store.stagedById(c, id) ?: return false
        Notifier.cancel(c, id)
        val delayMs = Store.deleteAfterMin(c) * 60_000L
        if (delayMs <= 0) return true
        Store.updateStaged(c, p.copy(deleteAt = System.currentTimeMillis() + delayMs))
        scheduleSweep(c)
        return false
    }

    /** Back to the Inbox from the Bin. */
    fun toInbox(c: Context, id: Long) {
        val p = Store.stagedById(c, id) ?: return
        Store.updateStaged(c, p.copy(deleteAt = 0L))
        scheduleSweep(c)
    }

    /** Bookkeeping after Android deleted these photos (createDeleteRequest deletes them itself). */
    fun deleted(c: Context, ids: Collection<Long>) {
        ids.forEach { id ->
            Notifier.cancel(c, id)
            val name = Store.stagedById(c, id)?.name ?: Store.approvedById(c, id)?.name
            Store.removeStaged(c, id)
            Store.removeApproved(c, id)
            Store.countDeleted(c)
            if (name != null) Store.log(c, "$name: deleted")
        }
    }

    // ------------------------------------------------------------------ Bin timer

    /** Bin photos whose timer ran out (deleted the next time PhotoGate is open, or from the notification). */
    fun dueIds(c: Context): List<Long> = Gate.due(Store.staged(c), System.currentTimeMillis()).map { it.id }

    /** Forget photos whose file vanished (deleted or moved by hand), list old uploads once, plan the next timer. */
    @Synchronized
    fun tidy(c: Context) {
        importExistingUploads(c)
        Store.staged(c).filter { !File(it.path).exists() }.forEach { Store.removeStaged(c, it.id) }
        Store.approved(c).filter { !File(it.path).exists() }.forEach { Store.removeApproved(c, it.id) }
        scheduleSweep(c)
    }

    fun scheduleSweep(c: Context) {
        val next = Gate.nextDue(Store.staged(c)) ?: return
        val delay = (next - System.currentTimeMillis()).coerceAtLeast(0L)
        val req = OneTimeWorkRequestBuilder<SweepWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(c.applicationContext).enqueueUniqueWork("sweep", ExistingWorkPolicy.REPLACE, req)
    }

    /**
     * Photos uploaded before the Uploaded tab existed have no record. Once, list what MediaStore has under
     * DCIM/PhotoGate so they show up there.
     */
    private fun importExistingUploads(c: Context) {
        if (Store.flag(c, "importedApproved") || !hasAccess(c)) return
        val known = Store.approved(c).map { it.path }.toSet()
        val folders = Store.folders(c)
        val found = Media.under(c, FOLDER).filter { it.file != null && it.file.path !in known }
        found.forEach { item ->
            val sub = item.folder.removePrefix(FOLDER).trimEnd('/')
            val cat = if (sub.isEmpty()) "" else Categories.fromFolder(sub)
            val from = folders.firstOrNull { Store.categoryOf(c, it) == cat } ?: "DCIM/Camera/"
            Store.addApproved(c, Photo(item.id, item.file!!.path, item.name, from, item.isVideo, item.addedMs, category = cat))
        }
        if (found.isNotEmpty()) Store.log(c, "Listed ${found.size} photos already in PhotoGate under Uploaded")
        Store.setFlag(c, "importedApproved")
    }
}

/** When a Bin timer runs out: Android only lets PhotoGate delete while it is on screen, so say it's time. */
class SweepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        Gallery.tidy(c)
        val due = Gallery.dueIds(c)
        if (due.isNotEmpty()) Notifier.postDue(c, due.size)
        return Result.success()
    }
}

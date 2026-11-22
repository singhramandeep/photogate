package com.raman.photogate

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What PhotoGate does with files. While waiting, a photo stays in its original folder.
 * Upload moves it into DCIM/PhotoGate/p<category> (synced by the gallery), Bin deletes it after the Bin timer,
 * Delete now deletes it at once.
 */
object Gallery {
    /** RELATIVE_PATH of the approved folder. Sync only this folder to Google Photos (Samsung Gallery). */
    const val FOLDER = "DCIM/PhotoGate/"

    private val sdcard: File get() = Environment.getExternalStorageDirectory()

    fun gate(): Gate = Gate(File(sdcard, FOLDER.trimEnd('/')))

    /** Deleting other apps' photos without a prompt each time needs "All files access" (one switch in settings). */
    fun hasAccess(): Boolean = previewAccess ?: runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /** Set only by screenshot tests, where the system cannot be asked. */
    var previewAccess: Boolean? = null

    fun accessIntent(c: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${c.packageName}"))

    /** Tell MediaStore (and so Google Photos and galleries) that these paths changed. */
    private fun rescan(c: Context, vararg paths: String) {
        MediaScannerConnection.scanFile(c.applicationContext, arrayOf(*paths), null, null)
    }

    /** Starts tracking a new photo. Nothing happens to the file. */
    fun track(c: Context, item: Item): Photo? {
        val f = item.file ?: return null
        return Photo(item.id, f.path, f.name, item.folder, item.isVideo, System.currentTimeMillis())
            .also { Store.addStaged(c, it) }
    }

    fun approve(c: Context, id: Long): Boolean {
        val p = Store.stagedById(c, id) ?: return false
        Notifier.cancel(c, id)
        return try {
            val cat = Store.categoryOf(c, p.from)
            val dst = gate().approve(File(p.path), cat)
            Describe.write(dst, cat) // before the rescan, so MediaStore and sync see the final file
            Store.removeStaged(c, id)
            Store.addApproved(c, p.copy(path = dst.path, name = dst.name, seenAt = System.currentTimeMillis(),
                deleteAt = 0L, category = cat))
            rescan(c, p.path, dst.path) // gone from its old folder, new in PhotoGate
            Store.countApproved(c)
            true
        } catch (e: IOException) {
            if (!File(p.path).exists()) Store.removeStaged(c, id) // original is gone, forget it
            false
        }
    }

    /** The original is deleted when the delay set in Settings is over (0 = right away). */
    fun discard(c: Context, id: Long) {
        val p = Store.stagedById(c, id) ?: return
        Notifier.cancel(c, id)
        val delayMs = Store.deleteAfterMin(c) * 60_000L
        if (delayMs <= 0) {
            deleteNow(c, id)
            return
        }
        Store.updateStaged(c, p.copy(deleteAt = System.currentTimeMillis() + delayMs))
        scheduleSweep(c)
    }

    /** Undo an approval: the photo goes back to its original folder and into the Inbox. */
    fun undoApprove(c: Context, id: Long): Boolean {
        val a = Store.approvedById(c, id) ?: return false
        val f = File(a.path)
        if (!f.exists()) {
            Store.removeApproved(c, id)
            return false
        }
        return try {
            val folder = File(sdcard, a.from.trimEnd('/'))
            val back = Gate.move(f, Gate.unique(folder, a.name))
            Store.removeApproved(c, id)
            Store.addStaged(c, Photo(a.id, back.path, back.name, a.from, a.isVideo, System.currentTimeMillis()))
            rescan(c, f.path, back.path)
            Store.log(c, "${a.name}: upload undone, back in ${a.from} and the Inbox")
            true
        } catch (e: IOException) {
            Store.log(c, "${a.name}: could not undo upload: ${e.message}")
            false
        }
    }

    /** Deletes an approved photo from PhotoGate (and so from the phone). */
    fun deleteApproved(c: Context, id: Long) {
        val a = Store.approvedById(c, id) ?: return
        val f = File(a.path)
        if (gate().delete(f)) {
            rescan(c, f.path)
            Store.removeApproved(c, id)
            Store.countDeleted(c)
        }
    }

    /** Takes a discarded photo back to the Inbox. */
    fun undiscard(c: Context, id: Long) {
        val p = Store.stagedById(c, id) ?: return
        Store.updateStaged(c, p.copy(deleteAt = 0L))
        scheduleSweep(c)
    }

    /** Deletes the original from its folder. */
    fun deleteNow(c: Context, id: Long) {
        val p = Store.stagedById(c, id) ?: return
        Notifier.cancel(c, id)
        val f = File(p.path)
        val base = if (p.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        // through MediaStore so galleries update at once; plain file delete as a fallback
        runCatching { c.contentResolver.delete(ContentUris.withAppendedId(base, p.id), null, null) }
        if (gate().delete(f)) {
            rescan(c, f.path)
            Store.removeStaged(c, id)
            Store.countDeleted(c)
        }
    }

    /** Deletes discarded photos whose time is up and schedules the next run. */
    @Synchronized
    fun sweep(c: Context) {
        restoreOldStaging(c)
        prefixOldFolders(c)
        importExistingApproved(c)
        val list = Store.staged(c)
        Gate.due(list, System.currentTimeMillis()).forEach { deleteNow(c, it.id) }
        // forget photos whose file vanished (deleted or moved by hand)
        list.filter { !File(it.path).exists() }.forEach { Store.removeStaged(c, it.id) }
        Store.approved(c).filter { !File(it.path).exists() }.forEach { Store.removeApproved(c, it.id) }
        scheduleSweep(c)
    }

    fun scheduleSweep(c: Context) {
        val next = Gate.nextDue(Store.staged(c)) ?: return
        val delay = (next - System.currentTimeMillis()).coerceAtLeast(0L)
        val req = OneTimeWorkRequestBuilder<SweepWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(c.applicationContext).enqueueUniqueWork("sweep", ExistingWorkPolicy.REPLACE, req)
    }

    /** Once: Camera -> pCamera etc. for folders made before the prefix, keeping the Uploaded list in step. */
    private fun prefixOldFolders(c: Context) {
        if (Store.flag(c, "prefixedFolders")) return
        val moved = gate().prefixOldFolders()
        if (moved.isNotEmpty()) {
            Store.approved(c).forEach { a ->
                moved[a.path]?.let { Store.addApproved(c, a.copy(path = it, name = File(it).name)) }
            }
            moved.entries.chunked(200).forEach { batch ->
                rescan(c, *(batch.map { it.key } + batch.map { it.value }).toTypedArray())
            }
            Store.log(c, "Renamed PhotoGate folders with a \"p\" prefix (${moved.size} files moved)")
        }
        Store.setFlag(c, "prefixedFolders")
    }

    /**
     * Photos approved before the Approved tab existed have no record. Once, list what is already in DCIM/PhotoGate
     * so it shows up there. Their original folder is unknown, so Undo sends them to a folder of the same category.
     */
    private fun importExistingApproved(c: Context) {
        if (Store.flag(c, "importedApproved")) return
        val root = gate().root
        val known = Store.approved(c).map { it.path }.toSet()
        val media = setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "mp4", "3gp", "mkv", "webm", "mov", "dng")
        val found = root.walkTopDown()
            .onEnter { it == root || !it.name.startsWith(".") }
            .filter { it.isFile && it.extension.lowercase() in media && it.path !in known }
            .sortedBy { it.lastModified() }
            .toList()
        val folders = Store.folders(c)
        found.forEach { f ->
            val cat = if (f.parentFile == root) "" else Categories.fromFolder(f.parentFile!!.name)
            val from = folders.firstOrNull { Store.categoryOf(c, it) == cat } ?: "DCIM/Camera/"
            val video = f.extension.lowercase() in setOf("mp4", "3gp", "mkv", "webm", "mov")
            Store.addApproved(c, Photo(-(f.path.hashCode().toLong() and 0x7fffffffL) - 1, f.path, f.name, from, video,
                f.lastModified(), category = cat))
        }
        if (found.isNotEmpty()) Store.log(c, "Listed ${found.size} photos already in PhotoGate under Approved")
        Store.setFlag(c, "importedApproved")
    }

    /**
     * The previous version moved waiting photos into DCIM/PhotoGate/.staging. Put each one back into the folder it
     * came from, keep its state (waiting or discarded), and remove the hidden folder once it is empty.
     */
    private fun restoreOldStaging(c: Context) {
        val staging = gate().oldStaging
        if (!staging.isDirectory) return
        val known = Store.staged(c).associateBy { it.path }
        staging.listFiles()?.filter { it.isFile && it.name != ".nomedia" }?.forEach { f ->
            val p = known[f.path]
            val folder = File(sdcard, (p?.from ?: "DCIM/Camera/").trimEnd('/'))
            try {
                val back = Gate.move(f, Gate.unique(folder, p?.name ?: f.name))
                rescan(c, back.path)
                if (p != null) Store.updateStaged(c, p.copy(path = back.path, name = back.name))
            } catch (e: IOException) {
                // leave it; tried again on the next sweep
            }
        }
        val left = staging.listFiles()?.filter { it.name != ".nomedia" }.orEmpty()
        if (left.isEmpty()) {
            File(staging, ".nomedia").delete()
            staging.delete()
        }
    }
}

class SweepWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Gallery.sweep(applicationContext)
        return Result.success()
    }
}

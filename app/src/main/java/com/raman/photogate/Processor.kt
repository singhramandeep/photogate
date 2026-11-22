package com.raman.photogate

import android.content.Context

/** Looks for photos that arrived since the last scan and decides what happens to each. */
object Processor {
    /** How far back each scan looks. Covers files that MediaStore only shows once they are finished. */
    const val LOOKBACK_SEC = 24 * 3600L

    /** A file must be this long untouched before it is handled, so the camera has finished writing it. */
    const val SETTLE_MS = 3_000L

    /** SKIP = not a watched folder, WAIT = still being written, ASK = Inbox + notification. */
    enum class Action { SKIP, WAIT, ASK }

    /** Pure decision for one new item (unit tested). */
    fun decide(folder: String, modifiedMs: Long, folders: Set<String>, nowMs: Long): Action {
        // PhotoGate's own folder (uploaded photos) is never asked about
        if (folder.startsWith(Gallery.FOLDER, ignoreCase = true)) return Action.SKIP
        if (folder !in folders) return Action.SKIP
        if (nowMs - modifiedMs < SETTLE_MS) return Action.WAIT
        return Action.ASK // every photo in a watched folder lands in the Inbox
    }

    /** What the "handled" memory should hold after a scan: old entries dropped so it stays small. */
    fun pruneHandled(handled: Map<Long, Long>, nowSec: Long): Map<Long, Long> =
        handled.filterValues { it >= nowSec - 2 * LOOKBACK_SEC }

    /** Why an item was left alone, for the activity log. */
    fun skipReason(folder: String, folders: Set<String>): String =
        when {
            folder.startsWith(Gallery.FOLDER, ignoreCase = true) -> "PhotoGate's own folder"
            folder !in folders -> "folder $folder is not switched on in Sources"
            else -> "skipped"
        }

    /** [again]: some files were still being written, look again shortly. [added]: new photos put in the Inbox. */
    data class Result(val again: Boolean, val added: Int)

    private val NOTHING = Result(false, 0)

    /** [scan] that never crashes the app: problems go to the activity log instead. */
    fun scanSafely(c: Context, why: String, perPhotoNotify: Boolean = true): Result = try {
        scan(c, why, perPhotoNotify)
    } catch (e: Throwable) {
        Store.log(c, "Scan failed: $e")
        NOTHING
    }

    @Synchronized
    fun scan(c: Context, why: String = "scan", perPhotoNotify: Boolean = true): Result {
        val app = c.applicationContext
        if (!Media.hasPermission(app)) {
            Store.log(app, "$why: no All files access, nothing checked")
            return NOTHING
        }

        val baseline = Store.lastSeen(app)
        if (baseline < 0) {
            // first run: everything already on the phone is left alone
            val max = Media.maxId(app)
            Store.setLastSeen(app, max)
            val what = if (max > 0) "photos already on the phone (up to #$max) are left alone" else "no photos yet"
            Store.log(app, "First run: $what. Photos taken from now on are caught.")
            return NOTHING
        }
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val handled = Store.handled(app)
        val fresh = Media.recent(app, nowSec - LOOKBACK_SEC, baseline).filter { it.id !in handled }
        if (fresh.isEmpty()) {
            if (why != "change") Store.log(app, "$why: nothing new")
            return NOTHING
        }

        val folders = Store.folders(app)
        // a tracked photo can show up again under a new id (e.g. put back from the old staging folder)
        val tracked = Store.staged(app).map { it.path }.toSet()
        val done = HashMap<Long, Long>()
        var again = false
        var added = 0

        for (item in fresh) {
            if (item.file?.path in tracked) {
                done[item.id] = item.addedMs / 1000
                continue
            }
            val label = "${item.name} (${item.folder})"
            when (decide(item.folder, item.modifiedMs, folders, nowMs)) {
                Action.WAIT -> {
                    again = true
                    Store.log(app, "$label: still being written, checking again shortly")
                    continue // not marked handled: looked at again next scan
                }
                Action.SKIP -> {
                    // our own folder is noisy (every upload lands there): not logged
                    if (!item.folder.startsWith(Gallery.FOLDER, ignoreCase = true)) {
                        Store.log(app, "$label: left alone, " + skipReason(item.folder, folders))
                    }
                }
                Action.ASK -> {
                    val p = Gallery.track(app, item)
                    if (p == null) Store.log(app, "$label: could not read its file path")
                    else {
                        added++
                        if (perPhotoNotify) Notifier.postAsk(app, p)
                        Store.log(app, "$label: added to Inbox")
                    }
                }
            }
            // every item is remembered, even in folders that are off: a folder switched on later only catches
            // what arrives from then on
            done[item.id] = item.addedMs / 1000
        }
        Store.setHandled(app, pruneHandled(handled + done, nowSec))
        return Result(again, added)
    }
}

package com.raman.photogate

import android.content.Context

/** Looks for photos that arrived since the last scan and decides what happens to each. */
object Processor {
    /** How far back each scan looks. Covers files that MediaStore only shows once they are finished. */
    const val LOOKBACK_SEC = 24 * 3600L

    /** A file must be this long untouched before it is handled, so the camera has finished writing it. */
    const val SETTLE_MS = 3_000L

    enum class Action { SKIP, WAIT, ASK, APPROVE }

    /** Pure decision for one new item (unit tested). */
    fun decide(
        folder: String, addedMs: Long, modifiedMs: Long,
        folders: Set<String>, mode: String, windowMs: Long, nowMs: Long,
    ): Action {
        // PhotoGate's own folder (the approved copies) is never asked about
        if (folder.startsWith(Gallery.FOLDER, ignoreCase = true)) return Action.SKIP
        if (folder !in folders) return Action.SKIP
        val taken = maxOf(addedMs, modifiedMs)
        if (windowMs > 0 && nowMs - taken > windowMs) return Action.SKIP
        if (mode == "none") return Action.SKIP // never asked, nothing happens
        if (nowMs - modifiedMs < SETTLE_MS) return Action.WAIT
        return if (mode == "all") Action.APPROVE else Action.ASK
    }

    /** What the "handled" memory should hold after a scan: old entries dropped so it stays small. */
    fun pruneHandled(handled: Map<Long, Long>, nowSec: Long): Map<Long, Long> =
        handled.filterValues { it >= nowSec - 2 * LOOKBACK_SEC }

    /** Returns true if some files were still being written and a later scan should look again. */
    @Synchronized
    fun scan(c: Context): Boolean {
        val app = c.applicationContext
        if (!Media.hasPermission(app)) return false

        val baseline = Store.lastSeen(app)
        if (baseline < 0) {
            // first run: everything already on the phone is left alone
            Store.setLastSeen(app, Media.maxId(app))
            return false
        }
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val handled = Store.handled(app)
        val fresh = Media.recent(app, nowSec - LOOKBACK_SEC, baseline).filter { it.id !in handled }
        if (fresh.isEmpty()) return false

        val folders = Store.folders(app)
        val mode = Store.mode(app)
        val windowMs = Store.windowMin(app) * 60_000L
        // a tracked photo can show up again under a new id (e.g. put back from the old staging folder)
        val tracked = Store.staged(app).map { it.path }.toSet()
        val done = HashMap<Long, Long>()
        var again = false

        for (item in fresh) {
            if (item.file?.path in tracked) {
                done[item.id] = item.addedMs / 1000
                continue
            }
            when (decide(item.folder, item.addedMs, item.modifiedMs, folders, mode, windowMs, nowMs)) {
                Action.WAIT -> {
                    again = true
                    continue // not marked handled: looked at again next scan
                }
                Action.SKIP -> Unit
                Action.APPROVE -> Gallery.approveDirect(app, item)
                Action.ASK -> Gallery.track(app, item)?.let { Notifier.postAsk(app, it) }
            }
            // every item is remembered, even in folders that are off: a folder switched on later only catches
            // what arrives from then on
            done[item.id] = item.addedMs / 1000
        }
        Store.setHandled(app, pruneHandled(handled + done, nowSec))
        return again
    }
}

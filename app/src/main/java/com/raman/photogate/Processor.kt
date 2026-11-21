package com.raman.photogate

import android.content.Context

/** Looks for photos that arrived since the last scan and decides what happens to each. */
object Processor {
    /** How far back each scan looks. Covers files that MediaStore only shows once they are finished. */
    const val LOOKBACK_SEC = 24 * 3600L

    enum class Action { SKIP, ASK, UPLOAD, UPLOAD_ALBUM }

    /** Pure decision for one new item (unit tested). */
    fun decide(
        folder: String, addedMs: Long, modifiedMs: Long,
        folders: Set<String>, mode: String, albumOn: Boolean, windowMs: Long, nowMs: Long,
    ): Action {
        if (folder !in folders) return Action.SKIP
        val taken = maxOf(addedMs, modifiedMs)
        if (windowMs > 0 && nowMs - taken > windowMs) return Action.SKIP
        return when (mode) {
            "none" -> Action.SKIP // stays on the phone, never asked
            "album" -> Action.UPLOAD_ALBUM
            "all" -> if (albumOn) Action.UPLOAD_ALBUM else Action.UPLOAD
            else -> Action.ASK
        }
    }

    /** What the "handled" memory should hold after a scan: old entries dropped so it stays small. */
    fun pruneHandled(handled: Map<Long, Long>, nowSec: Long): Map<Long, Long> =
        handled.filterValues { it >= nowSec - 2 * LOOKBACK_SEC }

    @Synchronized
    fun scan(c: Context) {
        val app = c.applicationContext
        if (!Media.hasPermission(app)) return

        val baseline = Store.lastSeen(app)
        if (baseline < 0) {
            // first run: everything already on the phone is left alone
            Store.setLastSeen(app, Media.maxId(app))
            return
        }
        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000
        val handled = Store.handled(app)
        val fresh = Media.recent(app, nowSec - LOOKBACK_SEC, baseline).filter { it.id !in handled }
        if (fresh.isEmpty()) return
        // remember every new item, even in folders that are off: a folder switched on later only asks about
        // what arrives from then on
        Store.setHandled(app, pruneHandled(handled + fresh.associate { it.id to it.addedMs / 1000 }, nowSec))

        val folders = Store.folders(app)
        val mode = Store.mode(app)
        val albumOn = Store.albumOn(app)
        val windowMs = Store.windowMin(app) * 60_000L

        for (item in fresh) {
            when (decide(item.folder, item.addedMs, item.modifiedMs, folders, mode, albumOn, windowMs, nowMs)) {
                Action.SKIP -> Unit
                Action.UPLOAD -> Uploader.enqueue(app, item.id, album = false)
                Action.UPLOAD_ALBUM -> Uploader.enqueue(app, item.id, album = true)
                Action.ASK -> {
                    Store.addPending(app, item.id)
                    Notifier.postAsk(app, item)
                }
            }
        }
    }
}

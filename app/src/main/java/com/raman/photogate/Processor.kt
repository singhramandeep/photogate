package com.raman.photogate

import android.content.Context

/** Looks for photos that arrived since the last scan and decides what happens to each. */
object Processor {
    @Synchronized
    fun scan(c: Context) {
        val app = c.applicationContext
        if (!Media.hasPermission(app)) return

        val last = Store.lastSeen(app)
        if (last < 0) {
            // first run: everything already on the phone is left alone
            Store.setLastSeen(app, Media.maxId(app))
            return
        }
        val fresh = Media.newerThan(app, last)
        if (fresh.isEmpty()) return
        Store.setLastSeen(app, fresh.maxOf { it.id })

        val folders = Store.folders(app)
        val mode = Store.mode(app)
        val windowMs = Store.windowMin(app) * 60_000L
        val now = System.currentTimeMillis()

        for (item in fresh) {
            if (item.folder !in folders) continue
            if (windowMs > 0 && now - item.addedMs > windowMs) continue
            when (mode) {
                "none" -> Unit // stays on the phone, never asked
                "all", "album" -> Uploader.enqueue(app, item.id)
                else -> {
                    Store.addPending(app, item.id)
                    Notifier.postAsk(app, item)
                }
            }
        }
    }
}

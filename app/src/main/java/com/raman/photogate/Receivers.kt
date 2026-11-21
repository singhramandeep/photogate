package com.raman.photogate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Handles the Upload / Discard buttons on a notification, without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        val app = context.applicationContext
        val action = intent.action
        Notifier.cancel(app, id)
        // moving a big video can take a moment; do it off the main thread
        val done = goAsync()
        Thread {
            try {
                when (action) {
                    Notifier.ACTION_UPLOAD -> Gallery.approve(app, id)
                    Notifier.ACTION_DISCARD -> Gallery.discard(app, id)
                    Notifier.ACTION_DELETE -> Gallery.deleteNow(app, id)
                }
            } finally {
                done.finish()
            }
        }.start()
    }
}

/** Restarts the watcher after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Store.watch(context)) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
            } catch (e: IllegalStateException) {
                // the system refused a background start; the watcher starts again next time the app opens
            }
        }
    }
}

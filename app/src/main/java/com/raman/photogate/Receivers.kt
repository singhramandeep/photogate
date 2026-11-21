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
        when (intent.action) {
            Notifier.ACTION_UPLOAD -> Uploader.approve(context, id)
            Notifier.ACTION_DISCARD -> Store.discard(context, id)
        }
        Notifier.cancel(context, id)
    }
}

/** Restarts the watcher after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Store.watch(context)) {
            ContextCompat.startForegroundService(context, Intent(context, WatchService::class.java))
        }
    }
}

package com.raman.photogate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

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

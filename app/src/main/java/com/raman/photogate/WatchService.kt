package com.raman.photogate

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore

/** Permanent notification + a ContentObserver, so a new photo is noticed right away. */
class WatchService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    private val scanNow = Runnable {
        Thread { Processor.scan(applicationContext) }.start()
    }

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // the camera fires several times per shot; wait for it to settle
            handler.removeCallbacks(scanNow)
            handler.postDelayed(scanNow, 1500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifier.channels(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, Notifier.watching(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            // "specialUse" only exists from Android 14
            startForeground(1, Notifier.watching(this))
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        scanNow.run()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        handler.removeCallbacks(scanNow)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

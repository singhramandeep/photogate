package com.raman.photogate

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity(), Actions {

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { Store.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Notifier.channels(this)
        setContent { PhotoGateTheme { App(this) } }
    }

    override fun onResume() {
        super.onResume()
        // back from the All files access screen, or from anywhere else: refresh screens
        Store.refresh()
        syncWatcher(this) // watching runs by itself while access is granted and a folder is on
        val app = applicationContext
        Thread {
            scanUntilSettled(app, "App opened")
            try {
                Gallery.sweep(app) // deletions that came due while the phone was off
            } catch (e: Throwable) {
                Store.log(app, "Cleanup failed: $e")
            }
        }.start()
    }

    override fun scanNow() {
        val app = applicationContext
        Thread { scanUntilSettled(app, "Scan now") }.start()
    }

    /** Scans, and again a few seconds later while a file is still being written (max ~20 s). */
    private fun scanUntilSettled(app: Context, why: String) {
        var tries = 0
        while (Processor.scanSafely(app, why) && tries++ < 5) Thread.sleep(Processor.SETTLE_MS + 500)
    }

    override fun askAccess() {
        try {
            startActivity(Gallery.accessIntent(this))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    override fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun openGallery() {
        val i = packageManager.getLaunchIntentForPackage("com.sec.android.gallery3d")
            ?: packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
        if (i != null) startActivity(i) else Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Watching is automatic: on while All files access is granted and at least one folder is switched on. */
        fun shouldWatch(c: Context): Boolean = Gallery.hasAccess() && Store.folders(c).isNotEmpty()

        fun syncWatcher(c: Context) {
            if (shouldWatch(c)) startWatching(c) else stopWatching(c)
        }

        fun startWatching(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, WatchService::class.java))
            } catch (e: IllegalStateException) {
                Toast.makeText(c, "Could not start the watcher: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        fun stopWatching(c: Context) {
            c.stopService(Intent(c, WatchService::class.java))
        }

        fun hasNotifications(c: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }
}

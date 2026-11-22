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
        if (Store.watch(this) && Gallery.hasAccess()) startWatching(this) // also restarts a refused boot start
        val app = applicationContext
        Thread {
            Processor.scan(app)
            Gallery.sweep(app) // deletions that came due while the phone was off
        }.start()
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

    override fun startWatching() = startWatching(this)
    override fun stopWatching() = stopWatching(this)

    override fun openGallery() {
        val i = packageManager.getLaunchIntentForPackage("com.sec.android.gallery3d")
            ?: packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
        if (i != null) startActivity(i) else Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
    }

    companion object {
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

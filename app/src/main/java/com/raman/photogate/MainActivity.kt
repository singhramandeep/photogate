package com.raman.photogate

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

    private val edits = Edits(this)

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { Store.refresh() }

    private val photosLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            Store.refresh()
            Scheduler.apply(this)
            // asked before, and said "don't ask again": only the settings page can change it now
            if (!Media.hasPermission(this) && !shouldShowRequestPermissionRationale(Media.permissions().first())) openAppSettings()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Notifier.channels(this)
        setContent { PhotoGateTheme { App(this) } }
    }

    override fun onResume() {
        super.onResume()
        // back from a settings screen, or from anywhere else: refresh screens
        Store.refresh()
        Scheduler.apply(this) // checking runs by itself while access is granted and a folder is on
        val app = applicationContext
        Thread {
            scanUntilSettled(app, "App opened")
            runCatching { Gallery.tidy(app) }.onFailure { Store.log(app, "Cleanup failed: $it") }
            // Bin timers that ran out: with Media management this needs no confirmation, so just do it
            if (Gallery.canManageMedia(app) && Gallery.dueIds(app).isNotEmpty()) runOnUiThread { sweep() }
        }.start()
    }

    override fun scanNow() {
        val app = applicationContext
        Thread { scanUntilSettled(app, "Scan now") }.start()
    }

    /** Scans, and again a few seconds later while a file is still being written (max ~20 s). */
    private fun scanUntilSettled(app: Context, why: String) {
        var tries = 0
        while (Processor.scanSafely(app, why).again && tries++ < 5) Thread.sleep(Processor.SETTLE_MS + 500)
    }

    // ---- permissions

    override fun askPhotos() {
        if (Gallery.partialAccess(this)) openAppSettings() else photosLauncher.launch(Media.permissions())
    }

    override fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun askManageMedia() {
        Gallery.manageMediaIntent(this)?.let {
            try {
                startActivity(it)
            } catch (e: ActivityNotFoundException) {
                openAppSettings()
            }
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    /** App info page: on Samsung, Battery > Unrestricted. Needs no special permission (Play-safe). */
    override fun openBatterySettings() {
        try {
            openAppSettings()
            Toast.makeText(this, "Tap Battery, then choose Unrestricted", Toast.LENGTH_LONG).show()
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun openGallery() {
        val i = packageManager.getLaunchIntentForPackage("com.sec.android.gallery3d")
            ?: packageManager.getLaunchIntentForPackage("com.google.android.apps.photos")
        if (i != null) startActivity(i) else Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show()
    }

    // ---- photo actions (Android may ask the user first; see Edits)

    override fun upload(ids: List<Long>) = edits.upload(ids)
    override fun bin(ids: List<Long>) = edits.bin(ids)
    override fun toInbox(ids: List<Long>) = ids.forEach { Gallery.toInbox(this, it) }
    override fun deleteNow(ids: List<Long>) = edits.deleteStaged(ids)
    override fun undoUpload(ids: List<Long>) = edits.undoUpload(ids)
    override fun deleteUploaded(ids: List<Long>) = edits.deleteUploaded(ids)
    override fun sweep() = edits.sweep { Notifier.cancelDue(this) }

    companion object {
        /** True when Android will not hold back PhotoGate's background checks to save battery. */
        fun batteryUnrestricted(c: Context): Boolean =
            c.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

        fun hasNotifications(c: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }
}

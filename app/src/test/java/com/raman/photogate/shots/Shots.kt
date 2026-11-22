package com.raman.photogate.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.raman.photogate.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import org.junit.Rule
import org.junit.Test
import java.io.File

class Shots {
    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false, maxPercentDifference = 1.0)

    private val ctx by lazy { MemContext(paparazzi.context) }

    private fun shot(dark: Boolean = false, content: @androidx.compose.runtime.Composable () -> Unit) {
        if (dark) paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(nightMode = NightMode.NIGHT))
        paparazzi.snapshot { CompositionLocalProvider(LocalContext provides ctx) { PhotoGateTheme { content() } } }
    }

    private fun fixtures() {
        val c = ctx
        val dir = File(System.getProperty("java.io.tmpdir"), "pgshots").apply { mkdirs() }
        val colors = listOf(0xFF8EA6C9, 0xFFD9A066, 0xFF6FA37C, 0xFFB07AA1, 0xFF7FB3C8, 0xFFCF7B6B)
        val now = System.currentTimeMillis()
        val folders = listOf("DCIM/Camera/", "Pictures/Screenshots/", "Pictures/WhatsApp/")
        colors.forEachIndexed { i, col ->
            val f = File(dir, "p$i.jpg")
            val b = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
            Canvas(b).apply {
                drawColor(col.toInt())
                drawCircle(260f, 150f, 70f, Paint().apply { color = Color.argb(120, 255, 255, 255) })
            }
            f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val discarded = i >= 4
            Store.addStaged(c, Photo(i.toLong() + 1, f.path, f.name, folders[i % 3], i == 2, now - i * 1000,
                if (discarded) now + (if (i == 4) 200 else 45) * 60_000L else 0L))
        }
        folders.forEach { Store.setFolder(c, it, true) }
        Store.addApproved(c, Photo(101, File(dir, "p0.jpg").path, "p0.jpg", "DCIM/Camera/", false, now, category = "Camera"))
        Store.addApproved(c, Photo(102, File(dir, "p1.jpg").path, "p1.jpg", "Pictures/Screenshots/", false, now - 5, category = "Screenshots", auto = true))
        Store.addApproved(c, Photo(103, File(dir, "p3.jpg").path, "p3.jpg", "Pictures/WhatsApp/", false, now - 9, category = "Social", auto = true))
    }

    @Test fun inbox() { fixtures(); shot { InboxScreen({}) } }
    @Test fun bin() { fixtures(); shot { BinScreen() } }
    @Test fun binDark() { fixtures(); shot(dark = true) { BinScreen() } }
    @Test fun emptyInbox() { shot { InboxScreen({}) } }
    private val noop = object : Actions {
        override fun askAccess() {}
        override fun askNotifications() {}
        override fun openGallery() {}
        override fun startWatching() {}
        override fun stopWatching() {}
        override fun scanNow() {}
    }

    @Test fun approved() { fixtures(); shot { ApprovedScreen() } }
    @Test fun inboxWithAutoApproveOn() { fixtures(); Store.startMode(ctx, "all", 3_600_000L); shot { InboxScreen({}) } }

    @Test fun app() { fixtures(); shot { App(noop) } }
    @Test fun settings() { fixtures(); Gallery.previewAccess = false; shot { SettingsScreen(noop) } }
    @Test fun sources() {
        fixtures()
        Gallery.previewAccess = true
        shot { SourcesScreen(listOf("DCIM/Camera/" to 2140, "Pictures/Screenshots/" to 512, "Pictures/WhatsApp/" to 930, "Download/" to 77)) }
    }
}

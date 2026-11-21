package com.raman.photogate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {
    const val CH_WATCH = "watch"
    const val CH_ASK = "ask"
    const val ACTION_UPLOAD = "com.raman.photogate.UPLOAD"
    const val ACTION_DISCARD = "com.raman.photogate.DISCARD"
    const val ACTION_DELETE = "com.raman.photogate.DELETE"
    private const val GROUP = "photogate_group"
    private const val SUMMARY_ID = 2

    /** Per-photo notification id. Offset so a low MediaStore id never collides with 1 (watcher), 2, 3. */
    fun idFor(mediaId: Long): Int = (mediaId % 1_000_000_000L).toInt() + 1000

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_WATCH, "Watching for new photos", NotificationManager.IMPORTANCE_LOW)
        )
        // LOW = no sound, no pop-over; it waits in the shade
        nm.createNotificationChannel(
            NotificationChannel(CH_ASK, "Approve or discard", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun openApp(c: Context): PendingIntent =
        PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun watching(c: Context): Notification =
        NotificationCompat.Builder(c, CH_WATCH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("PhotoGate is watching for new photos")
            .setContentText("New photos stay where they are until you decide")
            .setOngoing(true)
            .setContentIntent(openApp(c))
            .build()

    private fun action(c: Context, act: String, id: Long): PendingIntent {
        val i = Intent(c, ActionReceiver::class.java).setAction(act).putExtra("id", id)
        return PendingIntent.getBroadcast(
            c, idFor(id), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun postAsk(c: Context, item: Photo) {
        channels(c)
        val bmp = Media.thumb(java.io.File(item.path), item.isVideo, 600)
        val kind = if (item.isVideo) "video" else "photo"
        val b = NotificationCompat.Builder(c, CH_ASK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Send this $kind to PhotoGate?")
            .setContentText("In ${Media.label(item.from)}")
            .setGroup(GROUP)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openApp(c))
            .addAction(0, "Approve", action(c, ACTION_UPLOAD, item.id))
            .addAction(0, "Discard", action(c, ACTION_DISCARD, item.id))
            .addAction(0, "Delete now", action(c, ACTION_DELETE, item.id))
        if (bmp != null) {
            b.setLargeIcon(bmp)
            b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp))
        }
        val nm = NotificationManagerCompat.from(c)
        try {
            nm.notify(idFor(item.id), b.build())
            val summary = NotificationCompat.Builder(c, CH_ASK)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Photos waiting for your decision")
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setSilent(true)
                .setContentIntent(openApp(c))
                .build()
            nm.notify(SUMMARY_ID, summary)
        } catch (e: SecurityException) {
            // notification permission not granted; photos still wait in the Pending tab
        }
    }

    fun cancel(c: Context, id: Long) {
        NotificationManagerCompat.from(c).cancel(idFor(id))
        // the group summary stays behind on its own once its last photo is gone, so clear it too
        val nm = c.getSystemService(NotificationManager::class.java)
        val left = nm.activeNotifications.any { it.notification.group == GROUP && it.id != SUMMARY_ID }
        if (!left) nm.cancel(SUMMARY_ID)
    }
}

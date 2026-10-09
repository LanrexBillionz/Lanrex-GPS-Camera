package com.lanrex.sitecam.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.lanrex.sitecam.MainActivity
import com.lanrex.sitecam.R
import com.lanrex.sitecam.ui.permissions.AppPermissions

/** All of SiteCam's notifications. */
class Notifier(private val context: Context) {

    fun createChannels() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STAMPING, "Stamping progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while SiteCam makes stamped copies."
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SITE_MODE, "Site Mode", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Site Mode watches for new camera photos."
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ATTENTION, "Needs your attention", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Photos that need a location, and stamping problems."
            },
        )
    }

    fun openAppIntent(destination: String = DEST_QUEUE): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_OPEN, destination)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            destination.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun stampingNotification(title: String, text: String?, percent: Int?): Notification =
        NotificationCompat.Builder(context, CHANNEL_STAMPING)
            .setSmallIcon(R.drawable.ic_stat_sitecam)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .apply { if (percent != null) setProgress(100, percent.coerceIn(0, 100), false) else setProgress(0, 0, true) }
            .build()

    fun updateStamping(title: String, text: String?, percent: Int?) {
        post(ID_STAMPING, stampingNotification(title, text, percent))
    }

    fun needsLocation(count: Int) {
        if (count <= 0) {
            cancel(ID_NEEDS_LOCATION)
            return
        }
        val text = if (count == 1) "1 photo or video has no location. Tap to choose one." else
            "$count photos or videos have no location. Tap to choose one."
        val n = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_sitecam)
            .setContentTitle("Location needed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        post(ID_NEEDS_LOCATION, n)
    }

    fun failed(name: String, message: String) {
        val n = NotificationCompat.Builder(context, CHANNEL_ATTENTION)
            .setSmallIcon(R.drawable.ic_stat_sitecam)
            .setContentTitle("Couldn't stamp $name")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        post(ID_FAILED, n)
    }

    fun finished(count: Int, album: String) {
        if (count <= 0) return
        val text = if (count == 1) "1 stamped copy saved in Pictures/$album." else "$count stamped copies saved in Pictures/$album."
        val n = NotificationCompat.Builder(context, CHANNEL_STAMPING)
            .setSmallIcon(R.drawable.ic_stat_sitecam)
            .setContentTitle("Stamping finished")
            .setContentText(text)
            .setAutoCancel(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(DEST_HOME))
            .build()
        post(ID_FINISHED, n)
    }

    fun cancel(id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }

    /** Posts (or updates) a notification built elsewhere, e.g. the Site Mode one. */
    fun postRaw(id: Int, notification: Notification) = post(id, notification)

    private fun post(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= 33 && !AppPermissions.granted(context, Manifest.permission.POST_NOTIFICATIONS)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Notifications not allowed.
        }
    }

    companion object {
        const val CHANNEL_STAMPING = "stamping"
        const val CHANNEL_SITE_MODE = "site_mode"
        const val CHANNEL_ATTENTION = "attention"

        const val ID_STAMPING = 1001
        const val ID_SITE_MODE = 1002
        const val ID_NEEDS_LOCATION = 1003
        const val ID_FAILED = 1004
        const val ID_FINISHED = 1005
        const val ID_RESTAMP = 1006

        const val EXTRA_OPEN = "com.lanrex.sitecam.OPEN"
        const val DEST_QUEUE = "queue"
        const val DEST_HOME = "home"
    }
}

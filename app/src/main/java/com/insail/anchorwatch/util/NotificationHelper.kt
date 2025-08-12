package com.insail.anchorwatch.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.insail.anchorwatch.R
import com.insail.anchorwatch.location.AnchorWatchService

object NotificationHelper {
    private const val CH_STATUS = "anchor_status"
    const val CH_ALERT = "anchor_alert"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = manager(ctx)
        if (nm.getNotificationChannel(CH_STATUS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, ctx.getString(R.string.ch_status), NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (nm.getNotificationChannel(CH_ALERT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, ctx.getString(R.string.ch_alert), NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    fun manager(ctx: Context): NotificationManager =
        ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun status(ctx: Context, text: String): Notification {
        return NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_anchor)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    fun headsUpWithActions(ctx: Context, text: String): Notification {
        val mutePI = androidx.core.app.PendingIntentCompat.getService(
            ctx, 1,
            android.content.Intent(ctx, AnchorWatchService::class.java).setAction(AnchorWatchService.ACTION_MUTE_ALARM),
            0, false
        )
        return NotificationCompat.Builder(ctx, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_anchor)
            .setContentTitle(ctx.getString(R.string.alarm_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, ctx.getString(R.string.mute), mutePI)
            .setAutoCancel(false)
            .build()
    }
}

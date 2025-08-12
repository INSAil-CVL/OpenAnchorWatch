package com.insail.anchorwatch.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.insail.anchorwatch.R

object NotificationHelper {
    private const val CH_STATUS = "status"
    private const val CH_ALERT = "alert"

    fun ensureChannels(ctx: Context) {
        val nm = manager(ctx)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, ctx.getString(R.string.ch_status), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, ctx.getString(R.string.ch_alert), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                setBypassDnd(true)
            }
        )
    }

    fun manager(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun status(ctx: Context, text: String): Notification = NotificationCompat.Builder(ctx, CH_STATUS)
        .setSmallIcon(R.drawable.ic_stat_anchor)
        .setContentTitle(ctx.getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    fun headsUp(ctx: Context, text: String): Notification = NotificationCompat.Builder(ctx, CH_ALERT)
        .setSmallIcon(R.drawable.ic_stat_anchor)
        .setContentTitle(ctx.getString(R.string.alarm_title))
        .setContentText(text)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setAutoCancel(false)
        .build()
}
package com.insail.anchorwatch.util

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

object AlertHelper {
    private var ringtone: Ringtone? = null

    fun soundAlarm(ctx: Context) {
        if (ringtone?.isPlaying == true) return
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val r = RingtoneManager.getRingtone(ctx, uri)
        if (Build.VERSION.SDK_INT >= 21) {
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
        ringtone = r
        r.play()
    }

    fun vibrate(ctx: Context) {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(3000, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(3000)
        }
    }

    fun stopAlarm() {
        try { ringtone?.stop() } catch (_: Exception) {}
        ringtone = null
    }
}

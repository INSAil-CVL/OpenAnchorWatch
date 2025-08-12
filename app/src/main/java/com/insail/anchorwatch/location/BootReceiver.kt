package com.insail.anchorwatch.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.insail.anchorwatch.util.Prefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Prefs.isArmed(context)) {
                // Start service; it will read anchor from Prefs
                context.startForegroundService(Intent(context, AnchorWatchService::class.java))
            }
        }
    }
}
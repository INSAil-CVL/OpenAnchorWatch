package com.insail.anchorwatch.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.Geofence
import com.insail.anchorwatch.util.AlertHelper
import com.insail.anchorwatch.util.NotificationHelper

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_EXIT || event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_DWELL) {
            AlertHelper.soundAlarm(context)
            AlertHelper.vibrate(context)
            NotificationHelper.manager(context).notify(103, NotificationHelper.headsUp(context, "Anchor dragging / outside zone"))
        }
    }
}
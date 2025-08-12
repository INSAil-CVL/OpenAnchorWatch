package com.insail.anchorwatch.location

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.*
import com.insail.anchorwatch.R
import com.insail.anchorwatch.util.NotificationHelper
import com.insail.anchorwatch.util.AlertHelper
import com.insail.anchorwatch.model.AnchorConfig
import com.insail.anchorwatch.util.Prefs

class AnchorWatchService : Service() {

    companion object {
        const val EXTRA_ANCHOR_LAT = "extra_anchor_lat"
        const val EXTRA_ANCHOR_LON = "extra_anchor_lon"
        const val EXTRA_ANCHOR_RADIUS = "extra_anchor_radius"
        private const val GEOFENCE_ID = "ANCHOR_GEOFENCE"
        private const val NOTIF_ID = 101
    }

    private lateinit var fused: FusedLocationProviderClient
    private lateinit var geofencing: GeofencingClient
    private var anchor: AnchorConfig? = null
    private var currentInterval = 60_000L

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        geofencing = LocationServices.getGeofencingClient(this)
        NotificationHelper.ensureChannels(this)
        startForeground(NOTIF_ID, NotificationHelper.status(this, getString(R.string.notif_armed)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val lat = intent?.getDoubleExtra(EXTRA_ANCHOR_LAT, Double.NaN) ?: Double.NaN
        val lon = intent?.getDoubleExtra(EXTRA_ANCHOR_LON, Double.NaN) ?: Double.NaN
        val radius = intent?.getFloatExtra(EXTRA_ANCHOR_RADIUS, Float.NaN) ?: Float.NaN
        if (!lat.isNaN() && !lon.isNaN() && !radius.isNaN()) {
            anchor = AnchorConfig(lat, lon, radius)
            Prefs.setAnchor(this, anchor!!)
            Prefs.setArmed(this, true)
            arm(anchor!!)
        } else if (Prefs.isArmed(this)) {
            // Re-arm from stored prefs
            Prefs.getAnchor(this)?.let { a -> anchor = a; arm(a) }
        }
        return START_STICKY
    }

    private fun arm(a: AnchorConfig) {
        val geofence = Geofence.Builder()
            .setRequestId(GEOFENCE_ID)
            .setCircularRegion(a.lat, a.lon, a.radiusMeters)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT or Geofence.GEOFENCE_TRANSITION_DWELL)
            .setLoiteringDelay(60_000)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .build()

        val pi = PendingIntent.getBroadcast(
            this, 0, Intent(this, GeofenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        geofencing.addGeofences(
            GeofencingRequest.Builder().setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER).addGeofence(geofence).build(),
            pi
        )

        requestLocationUpdates(60_000L)
    }

    private fun requestLocationUpdates(intervalMs: Long) {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(true)
            .build()
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        currentInterval = intervalMs
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.lastLocation?.let { onNewLocation(it) } }
    }

    private fun onNewLocation(loc: Location) {
        val a = anchor ?: return
        val d = distanceMeters(loc.latitude, loc.longitude, a.lat, a.lon)
        val guard = a.radiusMeters * 0.8f

        val text = getString(R.string.notif_status_fmt, d.toInt(), a.radiusMeters.toInt())
        NotificationHelper.manager(this).notify(101, NotificationHelper.status(this, text))

        when {
            d >= a.radiusMeters -> triggerAlarm(getString(R.string.alarm_outside_fmt, d.toInt()))
            d >= guard -> bumpFrequency(20_000L)
            else -> bumpFrequency(60_000L)
        }

        // TODO Room: persist trace if needed
    }

    private fun triggerAlarm(msg: String) {
        AlertHelper.soundAlarm(this)
        AlertHelper.vibrate(this)
        NotificationHelper.manager(this).notify(102, NotificationHelper.headsUp(this, msg))
    }

    private fun bumpFrequency(newInterval: Long) {
        if (newInterval == currentInterval) return
        fused.removeLocationUpdates(callback)
        requestLocationUpdates(newInterval)
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        geofencing.removeGeofences(listOf("ANCHOR_GEOFENCE"))
        Prefs.setArmed(this, false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val r = FloatArray(1)
    Location.distanceBetween(lat1, lon1, lat2, lon2, r)
    return r[0]
}
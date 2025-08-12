package com.insail.anchorwatch.location

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.insail.anchorwatch.R
import com.insail.anchorwatch.model.AnchorConfig
import com.insail.anchorwatch.util.AlertHelper
import com.insail.anchorwatch.util.NotificationHelper
import com.insail.anchorwatch.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class AnchorWatchService : Service() {

    companion object {
        const val EXTRA_ANCHOR_LAT = "extra_anchor_lat"
        const val EXTRA_ANCHOR_LON = "extra_anchor_lon"
        const val EXTRA_ANCHOR_RADIUS = "extra_anchor_radius"

        const val ACTION_SET_INTERVAL = "action_set_interval"
        const val EXTRA_INTERVAL_MS = "extra_interval_ms"

        const val ACTION_MUTE_ALARM = "action_mute_alarm"

        const val ACTION_APP_FOREGROUND = "action_app_foreground"
        const val EXTRA_FOREGROUND = "extra_foreground"

        private const val NOTIF_ID = 101
        private const val NOTIF_ALERT_ID = 102

        const val ACTION_TRACE_POINT = "com.insail.anchorwatch.TRACE_POINT"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
    }

    private lateinit var fused: FusedLocationProviderClient
    private var anchor: AnchorConfig? = null

    private var userIntervalMs: Long = 60_000L
    private var appInForeground = false

    private var isAlarming = false
    private var mutedUntilMs = 0L

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        NotificationHelper.ensureChannels(this)
        userIntervalMs = (Prefs.getIntervalSec(this) ?: 60) * 1000L
        startForeground(NOTIF_ID, buildStatusNotification(getString(R.string.service_running)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_APP_FOREGROUND -> {
                appInForeground = intent.getBooleanExtra(EXTRA_FOREGROUND, false)
                val target = if (appInForeground) 1_000L else userIntervalMs
                requestLocationUpdates(target)
                updateStatus(
                    if (appInForeground)
                        getString(R.string.update_interval_seconds, 1)
                    else
                        getString(R.string.interval_applied, (userIntervalMs / 1000).toInt())
                )
                return START_STICKY
            }
            ACTION_SET_INTERVAL -> {
                val ms = intent.getLongExtra(EXTRA_INTERVAL_MS, userIntervalMs)
                userIntervalMs = ms.coerceIn(15_000L, 120_000L)
                if (!appInForeground) {
                    requestLocationUpdates(userIntervalMs) // seulement si arrière-plan
                    updateStatus(getString(R.string.interval_applied, (userIntervalMs / 1000).toInt()))
                }
                return START_STICKY
            }
            ACTION_MUTE_ALARM -> {
                AlertHelper.stopAlarm()
                isAlarming = false
                mutedUntilMs = System.currentTimeMillis() + 2 * 60_000L
                NotificationHelper.manager(this).cancel(NOTIF_ALERT_ID)
                updateStatus(getString(R.string.muted_for_2min))
                return START_STICKY
            }
            else -> {
                val lat = intent?.getDoubleExtra(EXTRA_ANCHOR_LAT, Double.NaN) ?: Double.NaN
                val lon = intent?.getDoubleExtra(EXTRA_ANCHOR_LON, Double.NaN) ?: Double.NaN
                val r = intent?.getFloatExtra(EXTRA_ANCHOR_RADIUS, Float.NaN) ?: Float.NaN
                if (!lat.isNaN() && !lon.isNaN() && !r.isNaN()) {
                    anchor = AnchorConfig(lat, lon, r)
                    requestLocationUpdates(if (appInForeground) 1_000L else userIntervalMs)
                    updateStatus(getString(R.string.armed))
                }
                return START_STICKY
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return granted
    }

    private fun requestLocationUpdates(intervalMs: Long) {
        if (!hasLocationPermission()) {
            updateStatus(getString(R.string.missing_location_permission))
            return
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setWaitForAccurateLocation(false)
            .build()
        fused.removeLocationUpdates(callback)
        try {
            fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            updateStatus(getString(R.string.missing_location_permission))
        }
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            onNewLocation(loc)
        }
    }

    private fun onNewLocation(loc: Location) {
        val a = anchor ?: return
        val d = distanceMeters(a.lat, a.lon, loc.latitude, loc.longitude)

        // Trace en DB (asynchrone)
        scope.launch {
            try {
                com.insail.anchorwatch.data.AppDatabase.get(this@AnchorWatchService)
                    .traceDao()
                    .insert(
                        com.insail.anchorwatch.data.TracePoint(
                            ts = System.currentTimeMillis(),
                            lat = loc.latitude,
                            lon = loc.longitude,
                            acc = loc.accuracy
                        )
                    )
            } catch (_: Exception) {}
        }

        com.insail.anchorwatch.data.TraceBus.emit(loc.latitude, loc.longitude)

        // push immédiat à l'UI
        sendBroadcast(Intent(ACTION_TRACE_POINT).apply {
            putExtra(EXTRA_LAT, loc.latitude)
            putExtra(EXTRA_LON, loc.longitude)
        })

        val now = System.currentTimeMillis()
        if (now < mutedUntilMs) return

        val sectorEnabled = Prefs.isSectorEnabled(this)
        val sectorDeg = Prefs.getSector(this)
        val headingDeg = Prefs.getHeading(this)
        val innerM = Prefs.getInnerRadius(this).toDouble()

        val allowed =
            if (!sectorEnabled || sectorDeg == 0) true
            else isInsideSector(a.lat, a.lon, loc.latitude, loc.longitude, headingDeg, sectorDeg)

        when {
            d < innerM -> { resetIfAlarming() }
            d <= a.radiusMeters && allowed -> { resetIfAlarming() }
            else -> triggerAlarmOnce(getString(R.string.alarm_outside_fmt, d.toInt()))
        }
    }

    private fun triggerAlarmOnce(msg: String) {
        if (isAlarming) return
        isAlarming = true
        AlertHelper.soundAlarm(this)
        AlertHelper.vibrate(this)
        NotificationHelper.manager(this)
            .notify(NOTIF_ALERT_ID, NotificationHelper.headsUpWithActions(this, msg))
        updateStatus(msg)
    }

    private fun resetIfAlarming() {
        if (!isAlarming) return
        AlertHelper.stopAlarm()
        isAlarming = false
        NotificationHelper.manager(this).cancel(NOTIF_ALERT_ID)
        updateStatus(getString(R.string.back_in_zone))
    }

    private fun updateStatus(text: String) {
        startForeground(NOTIF_ID, buildStatusNotification(text))
    }

    private fun buildStatusNotification(text: String): Notification =
        NotificationHelper.status(this, text)

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        AlertHelper.stopAlarm()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}

/** Outils géo */
private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6_378_137.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
    return 2 * R * asin(min(1.0, sqrt(a)))
}

private fun isInsideSector(
    lat0: Double, lon0: Double, lat: Double, lon: Double,
    headingDeg: Int, sectorDeg: Int
): Boolean {
    val brg = bearingDeg(lat0, lon0, lat, lon)
    val half = sectorDeg / 2.0
    val start = (headingDeg - half + 360) % 360
    val end = (headingDeg + half) % 360
    return if (start <= end) brg in start..end else (brg >= start || brg <= end)
}

private fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val φ1 = Math.toRadians(lat1); val φ2 = Math.toRadians(lat2)
    val λ1 = Math.toRadians(lon1); val λ2 = Math.toRadians(lon2)
    val y = sin(λ2 - λ1) * cos(φ2)
    val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(λ2 - λ1)
    val θ = Math.atan2(y, x)
    val deg = Math.toDegrees(θ)
    return (deg + 360) % 360
}

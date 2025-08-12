package com.insail.anchorwatch.util

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.insail.anchorwatch.model.AnchorConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.dataStore by preferencesDataStore(name = "anchor_prefs")

object Prefs {
    private val K_ARMED = booleanPreferencesKey("armed")
    private val K_LAT = doublePreferencesKey("anchor_lat")
    private val K_LON = doublePreferencesKey("anchor_lon")
    private val K_RADIUS = floatPreferencesKey("anchor_radius")
    private val K_INNER_RADIUS = intPreferencesKey("inner_radius_m") // 5,10,15,20

    private val K_INTERVAL = intPreferencesKey("interval_sec")        // 15..120
    private val K_SECTOR_ENABLED = booleanPreferencesKey("sector_enabled")
    private val K_HEADING = intPreferencesKey("heading_deg")          // 0..359
    private val K_SECTOR = intPreferencesKey("sector_deg")            // 0..360

    fun isArmed(ctx: Context): Boolean = runBlocking {
        ctx.dataStore.data.first()[K_ARMED] ?: false
    }

    fun getAnchor(ctx: Context): AnchorConfig? = runBlocking {
        val p = ctx.dataStore.data.first()
        val lat = p[K_LAT] ?: return@runBlocking null
        val lon = p[K_LON] ?: return@runBlocking null
        val r = p[K_RADIUS] ?: return@runBlocking null
        AnchorConfig(lat, lon, r)
    }

    fun setArmed(ctx: Context, armed: Boolean) = runBlocking {
        ctx.dataStore.edit { it[K_ARMED] = armed }
    }

    fun setAnchor(ctx: Context, a: AnchorConfig) = runBlocking {
        ctx.dataStore.edit { it[K_LAT] = a.lat; it[K_LON] = a.lon; it[K_RADIUS] = a.radiusMeters }
    }


    fun setInnerRadius(ctx: Context, meters: Int) = runBlocking {
        ctx.dataStore.edit { it[K_INNER_RADIUS] = meters.coerceIn(1, 100) }
    }
    fun getInnerRadius(ctx: Context): Int = runBlocking {
        ctx.dataStore.data.first()[K_INNER_RADIUS] ?: 5
    }

    fun setIntervalSec(ctx: Context, sec: Int) = runBlocking {
        ctx.dataStore.edit { it[K_INTERVAL] = sec.coerceIn(15, 120) }
    }
    fun getIntervalSec(ctx: Context): Int? = runBlocking { ctx.dataStore.data.first()[K_INTERVAL] }

    fun setSectorEnabled(ctx: Context, enabled: Boolean) = runBlocking {
        ctx.dataStore.edit { it[K_SECTOR_ENABLED] = enabled }
    }
    fun isSectorEnabled(ctx: Context): Boolean = runBlocking {
        ctx.dataStore.data.first()[K_SECTOR_ENABLED] ?: false
    }

    fun setHeading(ctx: Context, deg: Int) = runBlocking {
        ctx.dataStore.edit { it[K_HEADING] = ((deg % 360) + 360) % 360 }
    }
    fun getHeading(ctx: Context): Int = runBlocking {
        ctx.dataStore.data.first()[K_HEADING] ?: 180
    }

    fun setSector(ctx: Context, deg: Int) = runBlocking {
        ctx.dataStore.edit { it[K_SECTOR] = deg.coerceIn(0, 360) }
    }
    fun getSector(ctx: Context): Int = runBlocking {
        ctx.dataStore.data.first()[K_SECTOR] ?: 0 // 0 => pas de secteur => cercle complet
    }
}

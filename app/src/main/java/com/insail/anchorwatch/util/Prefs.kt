package com.insail.anchorwatch.util

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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

    fun isArmed(ctx: Context): Boolean = runBlocking {
        val prefs = ctx.dataStore.data.first()
        prefs[K_ARMED] ?: false
    }

    fun getAnchor(ctx: Context): AnchorConfig? = runBlocking {
        val prefs = ctx.dataStore.data.first()
        val lat = prefs[K_LAT] ?: return@runBlocking null
        val lon = prefs[K_LON] ?: return@runBlocking null
        val r = prefs[K_RADIUS] ?: return@runBlocking null
        AnchorConfig(lat, lon, r)
    }

    fun setArmed(ctx: Context, armed: Boolean) = runBlocking {
        ctx.dataStore.edit { it[K_ARMED] = armed }
    }

    fun setAnchor(ctx: Context, a: AnchorConfig) = runBlocking {
        ctx.dataStore.edit { prefs ->
            prefs[K_LAT] = a.lat
            prefs[K_LON] = a.lon
            prefs[K_RADIUS] = a.radiusMeters
        }
    }
}

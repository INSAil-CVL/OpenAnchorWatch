package com.insail.anchorwatch

import android.app.Application
import org.maplibre.android.MapLibre

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // OK avec OSM/OpenSeaMap : pas besoin d’apiKey ni de WellKnownTileServer
        MapLibre.getInstance(this)
    }
}

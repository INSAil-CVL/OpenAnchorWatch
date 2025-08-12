package com.insail.anchorwatch.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object TraceBus {
    // buffer pour ne pas perdre d’events si l’UI est un poil en retard
    private val _points = MutableSharedFlow<Pair<Double, Double>>(
        extraBufferCapacity = 64
    )
    val points: SharedFlow<Pair<Double, Double>> = _points

    fun emit(lat: Double, lon: Double) {
        _points.tryEmit(lat to lon)
    }
}

package com.insail.anchorwatch.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trace_points")
data class TracePoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val lat: Double,
    val lon: Double,
    val acc: Float
)

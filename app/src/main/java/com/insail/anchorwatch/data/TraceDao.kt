package com.insail.anchorwatch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TraceDao {
    @Insert suspend fun insert(p: TracePoint)
    @Query("SELECT * FROM trace_points ORDER BY ts DESC LIMIT :n")
    suspend fun latest(n: Int): List<TracePoint>
}
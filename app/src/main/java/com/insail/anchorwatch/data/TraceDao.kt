package com.insail.anchorwatch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TraceDao {
    @Insert suspend fun insert(p: TracePoint)

    @Query("SELECT * FROM trace_points ORDER BY ts DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<TracePoint>

    // NEW: flux live pour le fragment au premier plan
    @Query("SELECT * FROM trace_points ORDER BY ts DESC LIMIT :limit")
    fun latestFlow(limit: Int): kotlinx.coroutines.flow.Flow<List<TracePoint>>

    @Query("DELETE FROM trace_points")
    suspend fun clearAll()
}


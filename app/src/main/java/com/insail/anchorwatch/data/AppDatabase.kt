package com.insail.anchorwatch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [TracePoint::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun traceDao(): TraceDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "anchorwatch.db").build().also { INSTANCE = it }
        }
    }
}
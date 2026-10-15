package com.netforge.inspector.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * File 11 — Room database singleton.
 */
@Database(
    entities = [NetworkLogEntity::class],
    version = 1,
    exportSchema = false
)
abstract class InspectorDatabase : RoomDatabase() {

    abstract fun networkLogDao(): NetworkLogDao

    companion object {
        @Volatile
        private var instance: InspectorDatabase? = null

        fun get(context: Context): InspectorDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    InspectorDatabase::class.java,
                    "netforge_inspector.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}

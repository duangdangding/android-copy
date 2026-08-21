package com.clipditto.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ClipItem::class], version = 1, exportSchema = false)
abstract class ClipDatabase : RoomDatabase() {

    abstract fun clipDao(): ClipDao

    companion object {
        @Volatile
        private var INSTANCE: ClipDatabase? = null

        fun get(context: Context): ClipDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClipDatabase::class.java,
                    "clipditto.db"
                ).build().also { INSTANCE = it }
            }
    }
}

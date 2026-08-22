package com.clipditto.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ClipItem::class], version = 2, exportSchema = false)
abstract class ClipDatabase : RoomDatabase() {

    abstract fun clipDao(): ClipDao

    companion object {
        @Volatile
        private var INSTANCE: ClipDatabase? = null

        /** v1 → v2：新增局域网同步来源字段 */
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clips ADD COLUMN remoteDeviceId TEXT")
                db.execSQL("ALTER TABLE clips ADD COLUMN remoteId INTEGER")
            }
        }

        fun get(context: Context): ClipDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClipDatabase::class.java,
                    "clipditto.db"
                ).addMigrations(MIGRATION_1_2).build().also { INSTANCE = it }
            }
    }
}

package com.clipditto.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ClipItem::class, TransferRecord::class],
    version = 3,
    exportSchema = false
)
abstract class ClipDatabase : RoomDatabase() {

    abstract fun clipDao(): ClipDao
    abstract fun transferDao(): TransferDao

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

        /** v2 → v3：新增文件共享的传送记录表 */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS transfer_records (
                        |id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        |fileName TEXT NOT NULL,
                        |savedPath TEXT NOT NULL,
                        |fileSize INTEGER NOT NULL,
                        |mimeType TEXT,
                        |fromDeviceId TEXT,
                        |fromDeviceName TEXT,
                        |timestamp INTEGER NOT NULL
                        |)""".trimMargin()
                )
            }
        }

        fun get(context: Context): ClipDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ClipDatabase::class.java,
                    "clipditto.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
    }
}

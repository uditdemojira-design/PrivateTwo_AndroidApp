package org.privatetwo.app.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        TransferEntity::class,
        CallRecordEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PrivateTwoDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun transferDao(): TransferDao
    abstract fun callRecordDao(): CallRecordDao

    companion object {
        @Volatile
        private var INSTANCE: PrivateTwoDatabase? = null

        fun getInstance(context: Context): PrivateTwoDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PrivateTwoDatabase::class.java,
                    "privatetwo_local.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

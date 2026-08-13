package com.fraudguard.monitor.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.fraudguard.monitor.data.local.dao.EventDao
import com.fraudguard.monitor.data.local.dao.UsedCommandDao
import com.fraudguard.monitor.data.local.dao.WhitelistDao
import com.fraudguard.monitor.data.local.entity.EventEntity
import com.fraudguard.monitor.data.local.entity.UsedCommandEntity
import com.fraudguard.monitor.data.local.entity.WhitelistEntity

/** requirements.md 20.1章: 監視端末ローカルDB(Room)。 */
@Database(
    entities = [EventEntity::class, WhitelistEntity::class, UsedCommandEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun whitelistDao(): WhitelistDao
    abstract fun usedCommandDao(): UsedCommandDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "fraudguard.db",
                ).build().also { instance = it }
            }
        }
    }
}

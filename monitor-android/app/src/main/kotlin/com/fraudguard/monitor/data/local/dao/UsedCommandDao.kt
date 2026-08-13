package com.fraudguard.monitor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fraudguard.monitor.data.local.entity.UsedCommandEntity

@Dao
interface UsedCommandDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIfAbsent(entity: UsedCommandEntity): Long

    @Query("SELECT EXISTS(SELECT 1 FROM used_commands WHERE commandId = :commandId)")
    suspend fun exists(commandId: String): Boolean
}

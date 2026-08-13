package com.fraudguard.monitor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fraudguard.monitor.data.local.entity.EventEntity

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity)

    @Query("SELECT * FROM events WHERE synced = 0 ORDER BY createdAtMillis ASC")
    suspend fun getUnsynced(): List<EventEntity>

    @Update
    suspend fun update(event: EventEntity)

    @Query("UPDATE events SET synced = 1 WHERE eventId = :eventId")
    suspend fun markSynced(eventId: String)
}

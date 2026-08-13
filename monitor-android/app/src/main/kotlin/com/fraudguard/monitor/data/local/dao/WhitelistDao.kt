package com.fraudguard.monitor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fraudguard.monitor.data.local.entity.WhitelistEntity

@Dao
interface WhitelistDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<WhitelistEntity>)

    @Query("SELECT * FROM whitelist_cache WHERE phoneNumberE164 = :e164 AND enabled = 1 LIMIT 1")
    suspend fun findByNumber(e164: String): WhitelistEntity?

    @Query("DELETE FROM whitelist_cache")
    suspend fun clear()
}

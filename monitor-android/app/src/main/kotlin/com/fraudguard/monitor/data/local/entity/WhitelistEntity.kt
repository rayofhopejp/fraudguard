package com.fraudguard.monitor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** requirements.md 6.2章: サーバーを正としたホワイトリストのオフライン用ローカルキャッシュ。 */
@Entity(tableName = "whitelist_cache")
data class WhitelistEntity(
    @PrimaryKey val entryId: String,
    val phoneNumberE164: String,
    val displayName: String,
    val enabled: Boolean,
    val syncedAtMillis: Long,
)

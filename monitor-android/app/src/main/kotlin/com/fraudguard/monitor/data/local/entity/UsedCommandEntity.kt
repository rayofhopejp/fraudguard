package com.fraudguard.monitor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** requirements.md 8.1章, 20.1章: 「同一コマンドを二重実行しないこと」を保証するための実行済みcommandId台帳。 */
@Entity(tableName = "used_commands")
data class UsedCommandEntity(
    @PrimaryKey val commandId: String,
    val executedAtMillis: Long,
)

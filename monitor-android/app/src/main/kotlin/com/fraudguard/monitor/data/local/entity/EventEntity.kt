package com.fraudguard.monitor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.fraudguard.monitor.risk.EventType
import com.fraudguard.monitor.risk.RiskLevel

/**
 * requirements.md 20.1章, 24章: 未送信イベントを保持するローカルDB。
 * `synced=false` のレコードをEventSyncWorkerが再送する。eventIdで冪等性を担保する。
 */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val eventId: String,
    val type: EventType,
    val riskLevel: RiskLevel,
    val title: String,
    val detail: String,
    val timestampMillis: Long,
    val metadataJson: String,
    val synced: Boolean = false,
    val createdAtMillis: Long,
)

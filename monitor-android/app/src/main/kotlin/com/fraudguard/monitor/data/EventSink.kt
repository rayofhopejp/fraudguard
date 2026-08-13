package com.fraudguard.monitor.data

import com.fraudguard.monitor.risk.EventMetadata
import com.fraudguard.monitor.risk.EventType
import com.fraudguard.monitor.risk.RiskLevel

/**
 * リスクイベントの報告先。実体はEventReporter(ローカル保存+送信)。
 * 通話の追跡などイベントを出すだけの側が、DBや通信の都合に依存しないようにするための境界。
 */
interface EventSink {
    suspend fun report(
        type: EventType,
        riskLevel: RiskLevel,
        title: String,
        detail: String,
        metadata: EventMetadata,
    )
}

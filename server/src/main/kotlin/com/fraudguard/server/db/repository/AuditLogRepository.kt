package com.fraudguard.server.db.repository

import com.fraudguard.server.db.tables.AuditLogs
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.insert

/** requirements.md 8.2章, 25章: 操作ログ。呼び出し元は既にdbQuery/トランザクション内にいる前提(同一トランザクションで記録するため)。 */
object AuditLogRepository {
    fun record(
        actorFamilyUserId: String? = null,
        actorDeviceId: String? = null,
        action: String,
        targetType: String? = null,
        targetId: String? = null,
        result: String,
        detail: String? = null,
    ) {
        AuditLogs.insert {
            it[id] = UUID.randomUUID().toString()
            it[AuditLogs.actorFamilyUserId] = actorFamilyUserId
            it[AuditLogs.actorDeviceId] = actorDeviceId
            it[AuditLogs.action] = action
            it[AuditLogs.targetType] = targetType
            it[AuditLogs.targetId] = targetId
            it[AuditLogs.result] = result
            it[AuditLogs.detail] = detail
            it[createdAt] = Instant.now()
        }
    }
}

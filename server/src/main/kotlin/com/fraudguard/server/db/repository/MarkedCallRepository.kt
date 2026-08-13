package com.fraudguard.server.db.repository

import com.fraudguard.server.db.dbQuery
import com.fraudguard.server.db.tables.FamilyMarkedCalls
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select

/**
 * requirements.md 10.3章: 家族が「これは家族の通話」とマークした通話の記録。
 *
 * LINE等のアプリ内通話は相手を電話番号で識別できないため、6章のホワイトリスト(番号単位)が使えない。
 * そこで通話単位でマークし、以降その通話に関するイベントは通知しないようにする。
 */
object MarkedCallRepository {

    suspend fun isMarked(deviceId: String, callId: String): Boolean = dbQuery {
        FamilyMarkedCalls
            .select { (FamilyMarkedCalls.deviceId eq deviceId) and (FamilyMarkedCalls.callId eq callId) }
            .empty()
            .not()
    }

    /**
     * Slackのリンクは何度でも押せるため、二重マークは黙って無視する(冪等)。
     * @return 新規にマークした場合true、既にマーク済みならfalse。
     */
    suspend fun mark(deviceId: String, callId: String, markedBy: String?): Boolean = dbQuery {
        val inserted = FamilyMarkedCalls.insertIgnore {
            it[FamilyMarkedCalls.id] = UUID.randomUUID().toString()
            it[FamilyMarkedCalls.deviceId] = deviceId
            it[FamilyMarkedCalls.callId] = callId
            it[markedAt] = Instant.now()
            it[FamilyMarkedCalls.markedBy] = markedBy
        }
        val newlyMarked = inserted.insertedCount > 0

        // requirements.md 8.2章: 通知を止める操作は監査対象。マークと同じトランザクションで残す。
        AuditLogRepository.record(
            actorFamilyUserId = markedBy,
            actorDeviceId = deviceId,
            action = "MARK_CALL_AS_FAMILY",
            targetType = "CALL",
            targetId = callId,
            result = "SUCCESS",
            detail = if (markedBy == null) "Slackのリンクから操作(操作者は特定不可)" else null,
        )
        newlyMarked
    }
}

package com.fraudguard.server.db.repository

import com.fraudguard.server.db.dbQuery
import com.fraudguard.server.db.tables.RemoteCommands
import com.fraudguard.server.domain.model.CommandType
import com.fraudguard.server.domain.model.RemoteCommand
import com.fraudguard.server.security.CommandKeys
import com.fraudguard.server.security.canonicalCommandPayload
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.update

/** requirements.md 8章: 遠隔コマンド。有効期限は短く保つ(対象通話がACTIVEな間だけ意味を持つため)。 */
private const val COMMAND_TTL_SECONDS = 120L

object CommandRepository {
    suspend fun create(deviceId: String, callId: String, type: CommandType, issuedByFamilyUserId: String): RemoteCommand = dbQuery {
        val commandId = UUID.randomUUID().toString()
        val issuedAt = Instant.now()
        val expiresAt = issuedAt.plus(COMMAND_TTL_SECONDS, ChronoUnit.SECONDS)
        val nonce = UUID.randomUUID().toString()

        val payload = canonicalCommandPayload(
            commandId = commandId,
            deviceId = deviceId,
            callId = callId,
            type = type.name,
            issuedAt = issuedAt.toString(),
            expiresAt = expiresAt.toString(),
            nonce = nonce,
        )
        val signature = CommandKeys.signer.sign(payload)

        RemoteCommands.insert {
            it[id] = commandId
            it[RemoteCommands.deviceId] = deviceId
            it[RemoteCommands.callId] = callId
            it[RemoteCommands.type] = type.name
            it[RemoteCommands.issuedByFamilyUserId] = issuedByFamilyUserId
            it[RemoteCommands.issuedAt] = issuedAt
            it[RemoteCommands.expiresAt] = expiresAt
            it[RemoteCommands.nonce] = nonce
            it[RemoteCommands.signature] = signature
            it[delivered] = false
        }

        RemoteCommand(
            commandId = commandId,
            deviceId = deviceId,
            callId = callId,
            type = type,
            issuedAt = issuedAt.toString(),
            expiresAt = expiresAt.toString(),
            nonce = nonce,
            signature = signature,
            issuedByFamilyUserId = issuedByFamilyUserId,
        )
    }

    /** requirements.md 8.1章[v2]: FCM未達時のフォールバックポーリング対象。未配信かつ未失効のもの。 */
    suspend fun listPending(deviceId: String): List<RemoteCommand> = dbQuery {
        val now = Instant.now()
        RemoteCommands
            .select { (RemoteCommands.deviceId eq deviceId) and (RemoteCommands.delivered eq false) and (RemoteCommands.expiresAt greater now) }
            .map { it.toRemoteCommand() }
    }

    suspend fun markDelivered(commandId: String) = dbQuery {
        RemoteCommands.update({ RemoteCommands.id eq commandId }) {
            it[delivered] = true
        }
    }

    /** requirements.md 8.2章: 実行結果の報告を記録する。 */
    suspend fun reportExecution(commandId: String, deviceId: String, success: Boolean, failureReason: String?, executedAt: Instant): Boolean = dbQuery {
        val updated = RemoteCommands.update({ (RemoteCommands.id eq commandId) and (RemoteCommands.deviceId eq deviceId) }) {
            it[executedSuccess] = success
            it[executedFailureReason] = failureReason
            it[RemoteCommands.executedAt] = executedAt
        }
        updated > 0
    }

    private fun ResultRow.toRemoteCommand() = RemoteCommand(
        commandId = this[RemoteCommands.id],
        deviceId = this[RemoteCommands.deviceId],
        callId = this[RemoteCommands.callId],
        type = CommandType.valueOf(this[RemoteCommands.type]),
        issuedAt = this[RemoteCommands.issuedAt].toString(),
        expiresAt = this[RemoteCommands.expiresAt].toString(),
        nonce = this[RemoteCommands.nonce],
        signature = this[RemoteCommands.signature],
        issuedByFamilyUserId = this[RemoteCommands.issuedByFamilyUserId],
    )
}

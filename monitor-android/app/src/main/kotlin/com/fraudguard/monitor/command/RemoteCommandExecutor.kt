package com.fraudguard.monitor.command

import com.fraudguard.monitor.data.local.dao.UsedCommandDao
import com.fraudguard.monitor.data.local.entity.UsedCommandEntity
import com.fraudguard.monitor.data.remote.RemoteCommandDto
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * requirements.md 8.1章: 遠隔切断コマンドの安全性検証。以下を全て満たさない限り実行しない。
 *  - 署名がserverPublicKeyで検証できること
 *  - expiresAtを過ぎていないこと
 *  - callIdが現在進行中の通話のいずれかと一致すること
 *  - commandIdが未使用(二重実行防止, UsedCommandDao)であること
 *
 * @param disconnectAction 実際の通話切断アクション。通常の電話は `FraudGuardInCallService`
 *        (ROLE_DIALER取得後にのみ存在する)、LINE等のアプリ内通話は通知が持つ切断用PendingIntent
 *        (requirements.md 10.3章)。どちらでも切れなかった場合は "call_unavailable" として拒否する。
 * @param clock テスト時に時刻を固定するためのフック。
 */
class RemoteCommandExecutor(
    private val verifier: CommandSignatureVerifier,
    private val usedCommandDao: UsedCommandDao,
    private val disconnectAction: (callId: String) -> Boolean,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun execute(command: RemoteCommandDto, activeCallIds: Set<String>): ExecutionResult {
        if (usedCommandDao.exists(command.commandId)) {
            return ExecutionResult.Rejected("duplicate_command")
        }

        val payload = canonicalCommandPayload(
            commandId = command.commandId,
            deviceId = command.deviceId,
            callId = command.callId,
            type = command.type,
            issuedAt = command.issuedAt,
            expiresAt = command.expiresAt,
            nonce = command.nonce,
        )
        // 署名やnonce等がBase64として不正な(壊れた/改ざんされた)場合もIllegalArgumentExceptionを
        // 握りつぶし、呼び出し元(FCM受信コルーチン)をクラッシュさせず拒否として扱う。
        val signatureValid = try {
            verifier.verify(payload, command.signature)
        } catch (e: IllegalArgumentException) {
            false
        }
        if (!signatureValid) {
            return ExecutionResult.Rejected("invalid_signature")
        }

        val expiresAt = try {
            Instant.parse(command.expiresAt)
        } catch (e: DateTimeParseException) {
            return ExecutionResult.Rejected("invalid_expiry_format")
        }
        val now = clock()
        if (now.isAfter(expiresAt)) {
            return ExecutionResult.Rejected("expired")
        }

        if (command.callId !in activeCallIds) {
            return ExecutionResult.Rejected("call_not_active_or_mismatched")
        }

        // requirements.md 8.1章: 二重実行防止のため、実行を試みる前に使用済みとして記録する
        // (このコマンドIDは常に一意に発行されるため、切断に失敗しても再試行は新しいコマンドで行う)。
        usedCommandDao.insertIfAbsent(UsedCommandEntity(commandId = command.commandId, executedAtMillis = now.toEpochMilli()))

        return if (disconnectAction(command.callId)) {
            ExecutionResult.Executed
        } else {
            ExecutionResult.Rejected("call_unavailable")
        }
    }
}

sealed class ExecutionResult {
    data object Executed : ExecutionResult()
    data class Rejected(val reason: String) : ExecutionResult()
}

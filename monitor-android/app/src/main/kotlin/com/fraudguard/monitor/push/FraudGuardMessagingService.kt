package com.fraudguard.monitor.push

import com.fraudguard.monitor.FraudGuardApplication
import com.fraudguard.monitor.call.FraudGuardInCallService
import com.fraudguard.monitor.command.CommandSignatureVerifier
import com.fraudguard.monitor.command.ExecutionResult
import com.fraudguard.monitor.command.RemoteCommandExecutor
import com.fraudguard.monitor.data.remote.ApiClient
import com.fraudguard.monitor.data.remote.CommandExecutionReportDto
import com.fraudguard.monitor.data.remote.RegisterFcmTokenRequest
import com.fraudguard.monitor.data.remote.RemoteCommandDto
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * requirements.md 8章[v2]: FCM Data Messageによる遠隔コマンド受信、および自端末のFCMトークン登録。
 * data messageのみを想定(notification表示はFamily Web側の通知であり、Monitor側はコマンド実行が主目的)。
 */
class FraudGuardMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val command = data.toRemoteCommandDto() ?: return

        val app = applicationContext as FraudGuardApplication
        val pairingRepository = app.pairingRepository
        val serverPublicKey = pairingRepository.getServerPublicKey() ?: return
        val apiKey = pairingRepository.getApiKey() ?: return
        val ownDeviceId = pairingRepository.getDeviceId() ?: return

        CoroutineScope(Dispatchers.IO).launch {
            val executor = RemoteCommandExecutor(
                verifier = CommandSignatureVerifier(serverPublicKey),
                usedCommandDao = app.database.usedCommandDao(),
            )
            // requirements.md 4.3章[v2]: ROLE_DIALER取得後はFraudGuardInCallServiceが「現在ACTIVEな通話」の
            //       正となる(CallMonitorServiceはROLE_DIALER未取得時のみの軽量監視のため使わない)。
            val result = executor.execute(command, FraudGuardInCallService.activeCallId())

            // requirements.md 8.1章[v2]: FCM未達に備えたcommands/pendingへのポーリングも別途行う想定
            //       (sync/EventSyncWorker相当の定期ジョブは別途実装、8章TODO参照)。
            val report = CommandExecutionReportDto(
                commandId = command.commandId,
                deviceId = ownDeviceId,
                success = result is ExecutionResult.Executed,
                failureReason = (result as? ExecutionResult.Rejected)?.reason,
                executedAt = Instant.now().toString(),
            )
            runCatching {
                ApiClient.create { apiKey }.reportCommandResult(ownDeviceId, command.commandId, report)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // requirements.md 8章[v2]: サーバーへ登録し、遠隔切断コマンドの即時配信先とする。未ペアリング時は何もしない。
        val pairingRepository = (applicationContext as FraudGuardApplication).pairingRepository
        val deviceId = pairingRepository.getDeviceId() ?: return
        val apiKey = pairingRepository.getApiKey() ?: return

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                ApiClient.create { apiKey }.postFcmToken(deviceId, RegisterFcmTokenRequest(token))
            }
        }
    }
}

private fun Map<String, String>.toRemoteCommandDto(): RemoteCommandDto? {
    val commandId = this["commandId"] ?: return null
    val deviceId = this["deviceId"] ?: return null
    val callId = this["callId"] ?: return null
    val type = this["type"] ?: return null
    val issuedAt = this["issuedAt"] ?: return null
    val expiresAt = this["expiresAt"] ?: return null
    val nonce = this["nonce"] ?: return null
    val signature = this["signature"] ?: return null
    return RemoteCommandDto(
        commandId = commandId,
        deviceId = deviceId,
        callId = callId,
        type = type,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        nonce = nonce,
        signature = signature,
    )
}

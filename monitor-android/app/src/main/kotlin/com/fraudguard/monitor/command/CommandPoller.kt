package com.fraudguard.monitor.command

import com.fraudguard.monitor.FraudGuardApplication
import com.fraudguard.monitor.data.remote.ApiClient
import com.fraudguard.monitor.data.remote.CommandExecutionReportDto
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * requirements.md 8.1章[v2]: FCM未達に備えたpendingコマンドのポーリング。
 *
 * 通話中は「今まさに切りたい」場面なのでWorkManagerの最短15分では遅すぎるため、
 * 通話が存在する間だけ短い間隔でポーリングする(通話が無い間は動かないのでバッテリー影響も限定的)。
 *
 * 通常の電話(FraudGuardInCallService)とLINE等のアプリ内通話(通知経由)の双方から使う。
 * アプリ内通話ではTelecomが数秒で通話を手放すため、InCallService側のポーリングは当てにできない
 * (実機で、コマンド発行の2秒後にサービスがアンバインドされ一度もポーリングされなかった)。
 */
class CommandPoller(
    private val app: FraudGuardApplication,
    private val activeCallIds: () -> Set<String>,
) {
    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
    }

    private var job: Job? = null

    fun startIfNeeded(scope: CoroutineScope, beforeEachPoll: suspend () -> Unit = {}) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                beforeEachPoll()
                runCatching { pollOnce() }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun pollOnce() {
        val deviceId = app.pairingRepository.getDeviceId() ?: return
        val apiKey = app.pairingRepository.getApiKey() ?: return
        val serverPublicKey = app.pairingRepository.getServerPublicKey() ?: return

        val api = ApiClient.create { apiKey }
        val pending = api.getPendingCommands(deviceId).body().orEmpty()
        if (pending.isEmpty()) return

        val executor = RemoteCommandExecutor(
            verifier = CommandSignatureVerifier(serverPublicKey),
            usedCommandDao = app.database.usedCommandDao(),
            disconnectAction = { callId -> app.disconnectCall(callId) },
        )

        for (command in pending) {
            val result = executor.execute(command, activeCallIds())
            api.reportCommandResult(
                deviceId,
                command.commandId,
                CommandExecutionReportDto(
                    commandId = command.commandId,
                    deviceId = deviceId,
                    success = result is ExecutionResult.Executed,
                    failureReason = (result as? ExecutionResult.Rejected)?.reason,
                    executedAt = Instant.now().toString(),
                ),
            )
        }
    }
}

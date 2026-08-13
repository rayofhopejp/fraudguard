package com.fraudguard.monitor.call

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.fraudguard.monitor.FraudGuardApplication
import com.fraudguard.monitor.command.CommandSignatureVerifier
import com.fraudguard.monitor.command.ExecutionResult
import com.fraudguard.monitor.command.RemoteCommandExecutor
import com.fraudguard.monitor.data.remote.ApiClient
import com.fraudguard.monitor.data.remote.CommandExecutionReportDto
import com.fraudguard.monitor.risk.EventMetadata
import com.fraudguard.monitor.risk.EventType
import com.fraudguard.monitor.risk.RiskLevel
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * requirements.md 8章, 4.3章[v2]: ROLE_DIALER取得後に有効化するInCallService。
 * デフォルト電話アプリになると、着信中/通話中のシステム標準UIは表示されなくなるため、
 * このサービスがCallの追加/削除を検知してInCallActivityを起動し、
 * requirements.md 8.1章の遠隔切断コマンドが参照する「現在ACTIVEな通話」の実体もここで保持する。
 */
class FraudGuardInCallService : InCallService() {

    companion object {
        @Volatile
        var instance: FraudGuardInCallService? = null
            private set

        private val _calls = MutableStateFlow<List<TrackedCall>>(emptyList())

        /** requirements.md ui/incall: InCallActivityが画面表示に使う、Android非依存の通話一覧。 */
        val calls: StateFlow<List<TrackedCall>> = _calls.asStateFlow()

        /** requirements.md 8.1章: RemoteCommandExecutorが「対象通話が現在ACTIVEであること」を検証する際に使う。 */
        fun activeCallId(): String? = _calls.value.firstOrNull { it.state == Call.STATE_ACTIVE }?.callId

        /**
         * requirements.md 8.1章[v2]: FCM未達に備えたpendingコマンドのポーリング間隔。
         * 通話中は「今まさに切りたい」場面なのでWorkManagerの最短15分では遅すぎるため、
         * 通話が存在する間だけこの短い間隔でポーリングする(通話が無い間は動かないのでバッテリー影響も限定的)。
         */
        private const val COMMAND_POLL_INTERVAL_MS = 5_000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob())
    private val activeCalls = mutableMapOf<String, Call>()
    private var pollJob: Job? = null

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            publishState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        activeCalls.values.forEach { it.unregisterCallback(callCallback) }
        pollJob?.cancel()
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val callId = UUID.randomUUID().toString()
        activeCalls[callId] = call
        call.registerCallback(callCallback)
        publishState()

        reportCallEvent(callId, call)
        startCommandPollingIfNeeded()

        // requirements.md 4.3章[v2]: システム標準の着信/通話中UIが出ないため、自前の画面を起動する。
        startActivity(
            Intent(this, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        activeCalls.entries.removeAll { it.value == call }
        publishState()

        if (activeCalls.isEmpty()) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    /**
     * requirements.md 22章: 通話イベントをサーバーへ送る。ここで採番したcallIdを含めることで、
     * 家族アプリが「この通話を切る」と特定できるようになる(8章の遠隔切断の前提)。
     * リスク判定自体はサーバー側のRiskEngine(7章)が行うため、端末側は控えめな値で報告する。
     */
    private fun reportCallEvent(callId: String, call: Call) {
        val app = applicationContext as? FraudGuardApplication ?: return
        val isIncoming = call.state == Call.STATE_RINGING
        val phoneNumber = call.details?.handle?.schemeSpecificPart

        serviceScope.launch {
            app.eventReporter.report(
                type = if (isIncoming) EventType.CALL_INCOMING else EventType.CALL_OUTGOING,
                riskLevel = RiskLevel.NOTICE,
                title = if (isIncoming) "着信" else "発信",
                detail = "通話を検知しました。",
                metadata = EventMetadata(
                    phoneNumber = phoneNumber,
                    callId = callId,
                    direction = if (isIncoming) "INCOMING" else "OUTGOING",
                ),
            )
        }
    }

    /** requirements.md 8.1章[v2]: 通話中のみpendingコマンドをポーリングし、遠隔切断を実行する。 */
    private fun startCommandPollingIfNeeded() {
        if (pollJob?.isActive == true) return

        val app = applicationContext as? FraudGuardApplication ?: return
        val pairingRepository = app.pairingRepository

        pollJob = serviceScope.launch {
            while (isActive) {
                val deviceId = pairingRepository.getDeviceId()
                val apiKey = pairingRepository.getApiKey()
                val serverPublicKey = pairingRepository.getServerPublicKey()

                if (deviceId != null && apiKey != null && serverPublicKey != null) {
                    runCatching {
                        val api = ApiClient.create { apiKey }
                        val pending = api.getPendingCommands(deviceId).body().orEmpty()
                        if (pending.isNotEmpty()) {
                            val executor = RemoteCommandExecutor(
                                verifier = CommandSignatureVerifier(serverPublicKey),
                                usedCommandDao = app.database.usedCommandDao(),
                            )
                            for (command in pending) {
                                val result = executor.execute(command, activeCallId())
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
                }
                delay(COMMAND_POLL_INTERVAL_MS)
            }
        }
    }

    private fun publishState() {
        _calls.value = activeCalls.map { (callId, call) ->
            TrackedCall(
                callId = callId,
                phoneNumber = call.details?.handle?.schemeSpecificPart,
                state = call.state,
            )
        }
    }

    /** requirements.md 8.1章: 呼び出し元(command.RemoteCommandExecutor)で検証済みであることが前提。 */
    fun disconnect(callId: String): Boolean {
        val call = activeCalls[callId] ?: return false
        call.disconnect()
        return true
    }

    fun answer(callId: String): Boolean {
        val call = activeCalls[callId] ?: return false
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
        return true
    }

    fun reject(callId: String): Boolean {
        val call = activeCalls[callId] ?: return false
        call.reject(false, null)
        return true
    }
}

/** InCallActivity(Compose)がandroid.telecom.Callに依存せずに描画できるようにするための投影。 */
data class TrackedCall(
    val callId: String,
    val phoneNumber: String?,
    val state: Int,
)

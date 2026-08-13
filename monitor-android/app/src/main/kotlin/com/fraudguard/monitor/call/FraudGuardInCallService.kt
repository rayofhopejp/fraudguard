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

        /**
         * requirements.md 7.4章: ホワイトリスト外番号との通話が続いた場合に家族へ知らせる閾値。
         * 3分で最初の警告を出し、以降5分・10分でも重ねて知らせる
         * (説得が長引くほど危険度が上がるため、一度警告して終わりにしない)。
         * 実際に警告とするかの判定はサーバー側RiskEngineが行う(ホワイトリスト判定を持つのはサーバー)。
         */
        private val LONG_CALL_THRESHOLDS_SECONDS = listOf(180L, 300L, 600L)
    }

    private val serviceScope = CoroutineScope(SupervisorJob())
    private val activeCalls = mutableMapOf<String, Call>()
    private var pollJob: Job? = null

    /** callId -> 通話時間監視ジョブ。requirements.md 4.2章の「ACTIVEからの経過時間計測」。 */
    private val durationJobs = mutableMapOf<String, Job>()

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            publishState()
            if (state == Call.STATE_ACTIVE) {
                val callId = activeCalls.entries.firstOrNull { it.value == call }?.key
                if (callId != null) startDurationTracking(callId, call)
            }
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
        // 発信は追加時点で既にACTIVE/DIALINGのことがあり、onStateChangedが来ない場合があるため
        // ここでも計測開始を試みる(startDurationTrackingは二重起動しない)。
        if (call.state == Call.STATE_ACTIVE) startDurationTracking(callId, call)

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
        val removedIds = activeCalls.filterValues { it == call }.keys.toList()
        activeCalls.entries.removeAll { it.value == call }
        publishState()

        // 通話が終わったら経過時間の監視も止める(残しておくと切れた通話で警告が飛ぶ)。
        removedIds.forEach { durationJobs.remove(it)?.cancel() }

        if (activeCalls.isEmpty()) {
            pollJob?.cancel()
            pollJob = null
        }

        // requirements.md 14.1章: 「通話中に指示されてアプリを入れさせられる」のが典型的な手口のため、
        // 通話終了直後にアプリの新規インストールを即スキャンする(定期スキャン待ちだと相関判定が遅れる)。
        val app = applicationContext as? FraudGuardApplication ?: return
        serviceScope.launch { runCatching { app.appInstallScanner.scan() } }
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

    /**
     * requirements.md 4.2章, 7.4章: 通話がACTIVEになった時点からの経過時間を計測し、
     * 閾値(3分・5分・10分)に達するたびにCALL_LONG_DURATIONイベントを送る。
     *
     * 通話終了後にまとめて送るのではなく通話中に送るのが要点で、家族が「今まさに長引いている通話」を
     * 知って遠隔切断(8章)を判断できるようにするための情報。
     */
    private fun startDurationTracking(callId: String, call: Call) {
        if (durationJobs[callId]?.isActive == true) return
        val app = applicationContext as? FraudGuardApplication ?: return
        val phoneNumber = call.details?.handle?.schemeSpecificPart

        durationJobs[callId] = serviceScope.launch {
            var elapsed = 0L
            for (threshold in LONG_CALL_THRESHOLDS_SECONDS) {
                delay((threshold - elapsed) * 1000)
                elapsed = threshold
                // 通話が既に終わっていたら報告しない(ジョブのキャンセルと競合した場合の保険)。
                if (!activeCalls.containsKey(callId)) return@launch

                val minutes = threshold / 60
                app.eventReporter.report(
                    type = EventType.CALL_LONG_DURATION,
                    riskLevel = RiskLevel.NOTICE, // 最終的なリスク判定はサーバー側RiskEngineが行う
                    title = "通話が${minutes}分を超えました",
                    detail = "通話が継続しています。",
                    metadata = EventMetadata(
                        phoneNumber = phoneNumber,
                        callId = callId,
                        durationSeconds = threshold,
                    ),
                )
            }
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

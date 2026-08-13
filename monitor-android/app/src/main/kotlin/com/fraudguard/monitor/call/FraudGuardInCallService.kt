package com.fraudguard.monitor.call

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.fraudguard.monitor.FraudGuardApplication
import com.fraudguard.monitor.command.CommandPoller
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

        /** packageName -> (callId, 記録時刻)。直近に報告したアプリ内通話。1アプリ1通話を想定。 */
        private val selfManagedCallIds = mutableMapOf<String, Pair<String, Long>>()

        /**
         * 通知が同じ通話のものとみなせる猶予。Telecomは数秒で通話を手放すため、
         * 「Telecomから消えた後に届いた通知」も同じ通話として引き継げる必要がある。
         * 一方、無期限に引き継ぐと次の通話が前回のcallIdを名乗ってしまうため上限を設ける。
         */
        private const val ADOPTION_WINDOW_MS = 60_000L

        /**
         * requirements.md 10.3章: 通知監視側が、同じ通話をTelecom経由で既に報告済みかを問い合わせる。
         * 引き継げばイベントが2件にならず、家族から見て1つの通話として扱える。
         */
        @Synchronized
        fun selfManagedCallId(packageName: String): String? {
            val (callId, rememberedAt) = selfManagedCallIds[packageName] ?: return null
            return callId.takeIf { System.currentTimeMillis() - rememberedAt <= ADOPTION_WINDOW_MS }
        }

        @Synchronized
        private fun rememberSelfManagedCall(packageName: String, callId: String) {
            selfManagedCallIds[packageName] = callId to System.currentTimeMillis()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob())
    private val activeCalls = mutableMapOf<String, Call>()
    private var commandPoller: CommandPoller? = null

    /** callId -> 通話時間監視ジョブ。requirements.md 4.2章の「ACTIVEからの経過時間計測」。 */
    private val durationJobs = mutableMapOf<String, Job>()

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            publishState()
            // アプリ内通話の通話時間は通知側が見る(requirements.md 10.3章)。
            if (state == Call.STATE_ACTIVE && !isSelfManaged(call)) {
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
        commandPoller?.stop()
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

        // requirements.md 10.3章: LINE等のアプリ内通話は、Telecomが数秒で通話を手放してしまう。
        // 通話時間の計測と遠隔切断は通話中ずっと残る通知側(AppCallRegistry)が引き継ぐため、
        // ここでは「どのcallIdで報告したか」だけを渡してこの先の追跡は行わない。
        // 自前の通話画面も出さない(LINE自身の通話画面を覆って通話の妨害になるため)。
        if (isSelfManaged(call)) {
            selfManagedSourceApp(call)?.let { rememberSelfManagedCall(it, callId) }
            return
        }

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

    /**
     * requirements.md 10.3章: LINE等のアプリ内通話かどうか。
     * これらはINCLUDE_SELF_MANAGED_CALLS宣言によって通知されるが、通話UIも操作もアプリ側の責務で、
     * こちらは「通話が起きている」という観測に徹する。
     */
    private fun isSelfManaged(call: Call): Boolean =
        call.details?.hasProperty(Call.Details.PROPERTY_SELF_MANAGED) == true

    /** 自己管理型通話の発生元アプリ(LINE等)。通常の電話ではnull。 */
    private fun selfManagedSourceApp(call: Call): String? =
        if (isSelfManaged(call)) call.details?.accountHandle?.componentName?.packageName else null

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        val removedIds = activeCalls.filterValues { it == call }.keys.toList()
        activeCalls.entries.removeAll { it.value == call }
        publishState()

        // 通話が終わったら経過時間の監視も止める(残しておくと切れた通話で警告が飛ぶ)。
        removedIds.forEach { durationJobs.remove(it)?.cancel() }

        if (activeCalls.isEmpty()) {
            commandPoller?.stop()
            commandPoller = null
        }

        // requirements.md 14.1章: 「通話中に指示されてアプリを入れさせられる」のが典型的な手口のため、
        // 通話終了直後にアプリの新規インストールと初回起動を即チェックする
        // (定期スキャン待ちだと相関判定が遅れる)。
        val app = applicationContext as? FraudGuardApplication ?: return
        serviceScope.launch {
            runCatching { app.appInstallScanner.scan() }
            runCatching { app.appLaunchDetector.checkLaunches() }
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
        val sourceApp = selfManagedSourceApp(call)

        serviceScope.launch {
            app.eventReporter.report(
                type = if (isIncoming) EventType.CALL_INCOMING else EventType.CALL_OUTGOING,
                riskLevel = RiskLevel.NOTICE,
                title = if (isIncoming) "着信" else "発信",
                detail = if (sourceApp != null) "アプリ内通話を検知しました。" else "通話を検知しました。",
                metadata = EventMetadata(
                    phoneNumber = phoneNumberOf(call),
                    callId = callId,
                    direction = if (isIncoming) "INCOMING" else "OUTGOING",
                    sourceApp = sourceApp,
                ),
            )
        }
    }

    /**
     * requirements.md 10.3章, 25章: 自己管理型通話のhandleは電話番号ではなくアプリ内の識別子のため、
     * phoneNumberとしては送らない。番号として扱うとサーバーのホワイトリスト照合が無意味になるうえ、
     * 電話番号ではない個人識別子を番号欄に残すことになる。
     */
    private fun phoneNumberOf(call: Call): String? =
        if (isSelfManaged(call)) null else call.details?.handle?.schemeSpecificPart

    /**
     * requirements.md 4.2章, 7.4章: 通話の経過時間を計測し、
     * 閾値(1分・3分・5分・10分・15分、以降15分おき)に達するたびにCALL_LONG_DURATIONイベントを送る。
     *
     * 通話終了後にまとめて送るのではなく通話中に送るのが要点で、家族が「今まさに長引いている通話」を
     * 知って遠隔切断(8章)を判断できるようにするための情報。
     *
     * 経過時間は自前のタイマー開始時刻ではなく、システムが持つ実際の接続時刻
     * (Call.Details.connectTimeMillis)から算出する。アプリのプロセスが通話中に落ちた場合
     * (クラッシュ・OEMのバッテリー最適化・アプリ更新)、サービス再バインド時に
     * 正しい経過時間で再開でき、既に過ぎた閾値は飛ばせるようにするため
     * (自前計測だと再起動後に経過時間が0に戻り、以降の警告が二度と出なかった)。
     */
    private fun startDurationTracking(callId: String, call: Call) {
        if (durationJobs[callId]?.isActive == true) return
        val app = applicationContext as? FraudGuardApplication ?: return
        val phoneNumber = phoneNumberOf(call)
        val sourceApp = selfManagedSourceApp(call)
        val connectTimeMillis = call.details?.connectTimeMillis?.takeIf { it > 0 } ?: System.currentTimeMillis()

        durationJobs[callId] = serviceScope.launch {
            // 通常の電話は1分から知らせる(requirements.md 7.4章)。
            for (threshold in longCallThresholdsSeconds(includeFirstMinute = true)) {
                val elapsedSeconds = (System.currentTimeMillis() - connectTimeMillis) / 1000
                val remaining = threshold - elapsedSeconds
                // プロセス再起動後などで既に過ぎている閾値は、今さら通知しても意味がないので飛ばす。
                if (remaining <= 0) continue
                delay(remaining * 1000)
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
                        sourceApp = sourceApp,
                    ),
                )
            }
        }
    }

    /** requirements.md 8.1章[v2]: 通話中のみpendingコマンドをポーリングし、遠隔切断を実行する。 */
    private fun startCommandPollingIfNeeded() {
        val app = applicationContext as? FraudGuardApplication ?: return
        val poller = commandPoller
            ?: CommandPoller(app, activeCallIds = { app.activeCallIds() }).also { commandPoller = it }

        poller.startIfNeeded(serviceScope) {
            // requirements.md 13章, 14.1章: 詐欺犯に指示されてアプリを入れさせられ、その場で
            // 起動させられるのが典型的な手口。通話中はインストールと起動の両方を毎回確認する。
            // インストールを先に検知しないと起動監視の対象に登録されないため、順序も重要
            // (起動チェックだけ回していて通話中のインストールを取りこぼす不具合を実機テストで発見)。
            runCatching { app.appInstallScanner.scan() }
            runCatching { app.appLaunchDetector.checkLaunches() }
        }
    }

    private fun publishState() {
        _calls.value = activeCalls.map { (callId, call) ->
            TrackedCall(
                callId = callId,
                phoneNumber = phoneNumberOf(call),
                state = call.state,
                isSelfManaged = isSelfManaged(call),
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

    /**
     * requirements.md 4.3章[v2]: 通話中の音声操作。標準の電話アプリを置き換える以上ここも代替が要る。
     * 親クラスの setMuted と同名にすると呼び出し先が紛らわしいため、別の名前にしている。
     */
    fun applyMuted(muted: Boolean) {
        setMuted(muted)
    }

    fun applySpeaker(on: Boolean) {
        setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
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
    /** requirements.md 10.3章: LINE等のアプリ内通話。監視はするが自前UIには出さない。 */
    val isSelfManaged: Boolean = false,
)

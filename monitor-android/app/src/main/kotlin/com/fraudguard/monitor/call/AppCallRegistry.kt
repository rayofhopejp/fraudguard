package com.fraudguard.monitor.call

import android.app.PendingIntent
import com.fraudguard.monitor.data.EventSink
import com.fraudguard.monitor.risk.EventMetadata
import com.fraudguard.monitor.risk.EventType
import com.fraudguard.monitor.risk.RiskLevel
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * requirements.md 10.3章: LINE等の「アプリ内通話」を通知経由で追跡する。
 *
 * なぜTelecom(InCallService)ではなくここで持つのか:
 * LINEは通話を自己管理型のPhoneAccountとして登録するが、実機で計測したところ
 * **通話開始から4〜7秒でTelecomから通話を取り下げる**(通話自体は続いている)。
 * そのためInCallService側のCallオブジェクトは即座に消え、通話時間の計測も遠隔切断もできない。
 *
 * 一方、通話中の常駐通知は通話が終わるまで残り続ける。しかもLINEはAOSP標準の
 * Notification.CallStyleを使っており、通知に切断用のPendingIntent(android.hangUpIntent)が
 * 載っている。したがって通知こそがアプリ内通話の実体に最も近く、
 * 開始検知・通話時間・遠隔切断のすべてをここから行う。
 */
class AppCallRegistry(
    private val eventReporter: EventSink,
    private val scope: CoroutineScope,
    /** 経過時間の基準となる時計。テストで仮想時間に合わせるために差し替える。 */
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * @param hangUpIntent 通話を切るためのPendingIntent。通知が提供していれば非null。
     *        CallStyleを使わないアプリでは取得できず、その場合は遠隔切断ができない。
     */
    data class AppCall(
        val callId: String,
        val packageName: String,
        val startedAtMillis: Long,
        val hangUpIntent: PendingIntent?,
    )

    /** packageName -> 進行中の通話。1アプリにつき1通話しか想定しない(LINEの挙動に合わせる)。 */
    private val calls = mutableMapOf<String, AppCall>()
    private val durationJobs = mutableMapOf<String, Job>()

    /** 進行中のアプリ内通話のcallId。遠隔切断コマンドの照合(8.1章)に使う。 */
    @Synchronized
    fun activeCallIds(): Set<String> = calls.values.map { it.callId }.toSet()

    @Synchronized
    fun hasActiveCall(): Boolean = calls.isNotEmpty()

    /**
     * requirements.md 10.3章: 通話中通知を検知した。既にTelecom経由で同じ通話を掴んでいれば
     * そのcallIdを引き継ぎ(イベントの二重報告を避ける)、掴んでいなければここで採番して報告する。
     *
     * @param startedAtMillis 通知が持つ通話開始時刻(Notification.when)。自前のタイマー開始時刻より
     *        正確で、アプリのプロセスが落ちて再バインドされた場合も正しい経過時間で再開できる。
     * @return 追跡対象となった通話。
     */
    @Synchronized
    fun onOngoingCallNotification(
        packageName: String,
        startedAtMillis: Long,
        hangUpIntent: PendingIntent?,
        adoptCallId: String?,
    ): AppCall {
        val existing = calls[packageName]
        if (existing != null) {
            // 通知は通話中に何度も更新される。切断用のPendingIntentは更新のたびに作り直されることが
            // あるため最新のものを保持し、それ以外は既存の追跡をそのまま続ける。
            val updated = existing.copy(hangUpIntent = hangUpIntent ?: existing.hangUpIntent)
            calls[packageName] = updated
            return updated
        }

        val call = AppCall(
            callId = adoptCallId ?: UUID.randomUUID().toString(),
            packageName = packageName,
            startedAtMillis = startedAtMillis,
            hangUpIntent = hangUpIntent,
        )
        calls[packageName] = call

        // Telecom経由で既に報告済みならここでは報告しない(同じ通話が2件になるのを避ける)。
        if (adoptCallId == null) {
            scope.launch {
                eventReporter.report(
                    type = EventType.CALL_INCOMING,
                    riskLevel = RiskLevel.NOTICE, // 最終的なリスク判定はサーバー側RiskEngineが行う
                    title = "着信",
                    detail = "アプリ内通話を検知しました。",
                    metadata = EventMetadata(
                        callId = call.callId,
                        direction = "INCOMING",
                        sourceApp = packageName,
                    ),
                )
            }
        }

        startDurationTracking(call)
        return call
    }

    /** requirements.md 10.3章: 通話中通知が消えた = 通話終了。 */
    @Synchronized
    fun onCallEnded(packageName: String) {
        val call = calls.remove(packageName) ?: return
        durationJobs.remove(call.callId)?.cancel()
    }

    /**
     * requirements.md 8.1章: 遠隔切断。通知が持つ切断用PendingIntentを発火する。
     * PendingIntentは発行元(LINE)の権限で実行されるため、こちらに特別な権限は要らない。
     *
     * @return 切断を要求できた場合true。対象の通話が無い、または通知が切断手段を
     *         提供していない(CallStyleを使っていない)場合はfalse。
     */
    @Synchronized
    fun hangUp(callId: String): Boolean {
        val call = calls.values.firstOrNull { it.callId == callId } ?: return false
        val intent = call.hangUpIntent ?: return false
        return runCatching { intent.send() }.isSuccess
    }

    /**
     * requirements.md 7.4章: 通話の経過時間を計測し、閾値に達するたびにイベントを送る。
     * 通話終了後にまとめてではなく通話中に送るのが要点で、家族が「今まさに長引いている通話」を
     * 知って介入を判断できるようにするための情報。
     */
    private fun startDurationTracking(call: AppCall) {
        durationJobs[call.callId] = scope.launch {
            // アプリ内通話は1分の通知を出さない(requirements.md 7.4章)。
            for (threshold in longCallThresholdsSeconds(includeFirstMinute = false)) {
                val elapsedSeconds = (nowMillis() - call.startedAtMillis) / 1000
                val remaining = threshold - elapsedSeconds
                // 既に過ぎている閾値は、今さら通知しても意味がないので飛ばす。
                if (remaining <= 0) continue
                delay(remaining * 1000)
                if (!isStillActive(call.callId)) return@launch

                eventReporter.report(
                    type = EventType.CALL_LONG_DURATION,
                    riskLevel = RiskLevel.NOTICE,
                    title = "通話が${threshold / 60}分を超えました",
                    detail = "アプリ内通話が継続しています。",
                    metadata = EventMetadata(
                        callId = call.callId,
                        durationSeconds = threshold,
                        sourceApp = call.packageName,
                    ),
                )
            }
        }
    }

    @Synchronized
    private fun isStillActive(callId: String): Boolean = calls.values.any { it.callId == callId }
}

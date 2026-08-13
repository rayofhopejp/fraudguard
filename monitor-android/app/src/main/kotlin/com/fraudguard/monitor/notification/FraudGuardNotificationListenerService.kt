package com.fraudguard.monitor.notification

import android.app.Notification
import android.app.PendingIntent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.fraudguard.monitor.FraudGuardApplication
import com.fraudguard.monitor.call.FraudGuardInCallService
import com.fraudguard.monitor.command.CommandPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * requirements.md 10.2章: LINE/Telegram/Signal/WhatsApp等の通知監視。
 * ユーザーが「設定 > 通知アクセス」で個別許可が必要(自動付与不可)。
 *
 * requirements.md 10.3章: アプリ内通話の検知・通話時間計測・遠隔切断もここが担う。
 * これらのアプリはAOSP標準のNotification.CallStyleで通話中通知を出しており、
 * 通知が通話の開始・継続・終了をそのまま表すうえ、切断用のPendingIntentまで載っている。
 * Telecom(InCallService)は数秒で通話を手放すため、通話中ずっと残るこの通知が唯一の足場になる
 * (詳細はAppCallRegistryのdocを参照)。
 *
 * requirements.md 35章[v2]: この許可がOSのバッテリー最適化等で失効した場合、
 * HeartbeatWorkerがisNotificationListenerEnabled相当のチェックを行い家族へ通知する。
 *
 * TODO: 10.1章の対象アプリのpackageNameフィルタリング拡張、
 *       11章の新規アプリ通知との突合、NOTIFICATION_OBSERVEDイベントの生成(confidence付き)。
 */
class FraudGuardNotificationListenerService : NotificationListenerService() {

    private val targetPackages = setOf(
        "jp.naver.line.android", // LINE
        "org.telegram.messenger", // Telegram
        "org.thoughtcrime.securesms", // Signal
        "com.whatsapp",
    )

    private val serviceScope = CoroutineScope(SupervisorJob())
    private var poller: CommandPoller? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in targetPackages) return
        val app = applicationContext as? FraudGuardApplication ?: return

        val hangUpIntent = hangUpIntentOf(sbn.notification)
        // requirements.md 10.3章: 切断用のPendingIntentを持つCallStyle通知＝通話中の常駐通知。
        // 通常のメッセージ通知と確実に区別できるので、これを通話の判定条件とする。
        if (hangUpIntent == null) {
            // TODO: 10.3章 通話以外の通知からの詐欺兆候推定(confidence付き)。
            return
        }

        app.appCallRegistry.onOngoingCallNotification(
            packageName = sbn.packageName,
            // Notification.when が通話開始時刻。自前の計測開始時刻より正確で、
            // プロセスが落ちて再バインドされた場合も正しい経過時間で再開できる。
            startedAtMillis = sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime,
            hangUpIntent = hangUpIntent,
            // 同じ通話をTelecom経由で既に報告済みならそのcallIdを引き継ぎ、二重報告を避ける。
            adoptCallId = FraudGuardInCallService.selfManagedCallId(sbn.packageName),
        )
        // requirements.md 8.1章[v2]: 通話中はpendingコマンドを短間隔でポーリングする。
        // アプリ内通話ではTelecomが数秒で手を引くため、InCallService側のポーリングは当てにできない。
        startPollingIfNeeded(app)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in targetPackages) return
        val app = applicationContext as? FraudGuardApplication ?: return

        // requirements.md 10.3章: 通話中通知が消えた = 通話終了。
        app.appCallRegistry.onCallEnded(sbn.packageName)
        if (!app.appCallRegistry.hasActiveCall()) {
            poller?.stop()
            poller = null
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        poller?.stop()
        poller = null
    }

    override fun onDestroy() {
        poller?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startPollingIfNeeded(app: FraudGuardApplication) {
        val poller = poller ?: CommandPoller(app, activeCallIds = { app.activeCallIds() }).also { this.poller = it }
        poller.startIfNeeded(serviceScope)
    }

    /**
     * requirements.md 10.3章: Notification.CallStyle が定める切断用PendingIntent。
     * EXTRA_ANSWER_INTENT等と同じくプラットフォームの規約なので、
     * LINEのUI構造に依存せず、CallStyleを使う他のアプリにもそのまま通用する。
     */
    private fun hangUpIntentOf(notification: Notification): PendingIntent? {
        val extras = notification.extras ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_HANG_UP_INTENT) as? PendingIntent
        }
    }
}

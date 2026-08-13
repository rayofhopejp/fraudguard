package com.fraudguard.monitor.notification

import android.app.Notification
import android.app.PendingIntent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.fraudguard.monitor.FraudGuardApplication
import com.fraudguard.monitor.call.FraudGuardInCallService
import com.fraudguard.monitor.command.CommandPoller
import com.fraudguard.monitor.risk.EventMetadata
import com.fraudguard.monitor.risk.EventType
import com.fraudguard.monitor.risk.RiskLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
 * TODO: 10.1章の対象アプリのpackageNameフィルタリング拡張(現状は主要4アプリ固定)。
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

    /** 同じ通知を繰り返し報告しないための記録。内容そのものは持たず、指紋だけを保持する。 */
    private val reportedNotifications = mutableSetOf<Int>()

    private companion object {
        const val MAX_REMEMBERED_NOTIFICATIONS = 500

        /** requirements.md 9.1章と同じ語句。詐欺の誘導はSMSでもメッセージアプリでも語彙が変わらない。 */
        val DANGEROUS_KEYWORDS = listOf(
            "ATM", "還付金", "振り込み", "振込", "暗証番号", "口座", "キャッシュカード",
            "コンビニ", "電子マネー", "投資", "仮想通貨", "遠隔操作", "アプリを入れて",
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in targetPackages) return
        val app = applicationContext as? FraudGuardApplication ?: return

        val hangUpIntent = hangUpIntentOf(sbn.notification)
        // requirements.md 10.3章: 切断用のPendingIntentを持つCallStyle通知＝通話中の常駐通知。
        // 通常のメッセージ通知と確実に区別できるので、これを通話の判定条件とする。
        if (hangUpIntent == null) {
            observeNonCallNotification(app, sbn)
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

    /**
     * requirements.md 10.3章: 通話以外の通知から詐欺の兆候を推定する。
     *
     * 対象アプリの通知をすべて報告することはしない。メッセージングアプリの通知は一日中発生し、
     * その大半は詐欺と無関係な私的な会話で、家族に全部見せるのは監視として行き過ぎであるうえ、
     * 30章が避けよと定めるアラート疲れをまねく。報告するのは次の2つだけに絞る。
     *
     * 1. 本文に詐欺でよく使われる語句を含むもの。SMS(9章)と同じ基準で、
     *    家族が判断するには本文が要るため本文も添える(16.3章[v3]と同じ判断)。
     * 2. 直近にインストールされたアプリからの通知。14.2章「通話 → メッセージングアプリ導入 →
     *    起動 → 通知発生」を成立させるために要る。こちらは「使われ始めた」ことが分かれば十分なので
     *    **本文は送らない**。
     */
    private fun observeNonCallNotification(app: FraudGuardApplication, sbn: StatusBarNotification) {
        val notification = sbn.notification
        // グループの見出しや常駐通知は、同じ内容が何度も届くだけで新しい情報がない。
        if (notification.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (text.isBlank()) return

        val matched = DANGEROUS_KEYWORDS.filter { text.contains(it) }
        val recentlyInstalled = app.appInstallScanner.isRecentlyInstalled(sbn.packageName)
        if (matched.isEmpty() && !recentlyInstalled) return

        // 通知は編集や既読で何度も更新される。同じ内容を繰り返し報告しない。
        val fingerprint = "${sbn.packageName}|$title|$text".hashCode()
        synchronized(reportedNotifications) {
            if (!reportedNotifications.add(fingerprint)) return
            if (reportedNotifications.size > MAX_REMEMBERED_NOTIFICATIONS) reportedNotifications.clear()
        }

        serviceScope.launch {
            app.eventReporter.report(
                type = EventType.NOTIFICATION_OBSERVED,
                riskLevel = RiskLevel.NOTICE, // 最終判定はサーバー側RiskEngineが行う
                title = "メッセージアプリの通知を検知しました",
                detail = if (matched.isEmpty()) {
                    "最近インストールされたアプリが通知を出しました。"
                } else {
                    "詐欺でよく使われる語句を含む通知を検知しました。"
                },
                metadata = EventMetadata(
                    packageName = sbn.packageName,
                    sourceApp = sbn.packageName,
                    // 語句に反応した場合のみ本文を送る。それ以外の通知の中身は送らない(25章)。
                    messageBody = if (matched.isEmpty()) null else text,
                    confidence = confidenceOf(matched.size, recentlyInstalled),
                ),
            )
        }
    }

    /**
     * requirements.md 10.3章: 通知からの推定は確実性が低いため信頼度を持たせる。
     * 語句が多く一致するほど高く、「最近入れたアプリだから」だけの観測は低く見積もる。
     */
    private fun confidenceOf(matchedCount: Int, recentlyInstalled: Boolean): Double = when {
        matchedCount > 0 -> minOf(0.5 + 0.15 * matchedCount, 0.95)
        recentlyInstalled -> 0.3
        else -> 0.0
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

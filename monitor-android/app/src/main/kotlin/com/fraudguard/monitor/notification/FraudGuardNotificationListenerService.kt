package com.fraudguard.monitor.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * requirements.md 10.2章: LINE/Telegram/Signal/WhatsApp等の通知監視。
 * ユーザーが「設定 > 通知アクセス」で個別に許可する必要がある(自動付与不可)。
 *
 * requirements.md 35章[v2]: この許可がOSのバッテリー最適化等で失効した場合、
 * HeartbeatWorkerがisNotificationListenerEnabled相当のチェックを行い家族へ通知する。
 *
 * TODO: 10.1章の対象アプリのpackageNameフィルタリング、10.3章のLINE通話兆候推定(信頼度付き)、
 *       11章の新規アプリ通知との突合。
 */
class FraudGuardNotificationListenerService : NotificationListenerService() {

    private val targetPackages = setOf(
        "jp.naver.line.android", // LINE
        "org.telegram.messenger", // Telegram
        "org.thoughtcrime.securesms", // Signal
        "com.whatsapp",
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in targetPackages) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        // TODO: 25章「不要な個人情報のログ出力禁止」— title/textをログに出さないこと。
        // TODO: NOTIFICATION_OBSERVED イベント生成(confidence付き) → EventRepositoryへ記録
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // TODO: HeartbeatWorkerへ「通知リスナー有効」を反映
    }
}

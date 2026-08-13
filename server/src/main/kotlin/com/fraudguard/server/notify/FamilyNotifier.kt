package com.fraudguard.server.notify

import com.fraudguard.server.domain.model.Event

/**
 * requirements.md 2.2章: 海外からの電話をとってしまった等の重要な通知はSlackへ送る、という方針に対応する。
 * FCM Web/Mobile Pushは別途Firebaseプロジェクトの構築が要る一方、Slack Incoming Webhookは
 * URLひとつで完結し個人・家族規模の運用に見合うため、家族への一次通知手段はこちらを優先する
 * (FCMは8章の遠隔コマンド配信(Monitorアプリへのdata message)専用の用途として別途検討する)。
 */
interface FamilyNotifier {
    suspend fun notify(event: Event, deviceName: String)
}

/** Webhook未設定のローカル開発環境向け。何もしない。 */
class NoopFamilyNotifier : FamilyNotifier {
    override suspend fun notify(event: Event, deviceName: String) {
        // 意図的に何もしない。
    }
}

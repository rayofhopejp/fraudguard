package com.fraudguard.server.notify

import com.fraudguard.server.domain.model.Event

/**
 * requirements.md 2.2章: 海外からの電話をとってしまった等の重要な通知はSlackへ送る、という方針に対応する。
 * FCM Web/Mobile Pushは別途Firebaseプロジェクトの構築が要る一方、Slack Incoming Webhookは
 * URLひとつで完結し個人・家族規模の運用に見合うため、家族への一次通知手段はこちらを優先する
 * (FCMは8章の遠隔コマンド配信(Monitorアプリへのdata message)専用の用途として別途検討する)。
 */
interface FamilyNotifier {
    /** @return 実際に配信できたか。失敗を握りつぶすと「警告が来ない=平常」と誤解されるため呼び出し元へ返す。 */
    suspend fun notify(event: Event, deviceName: String): Boolean
}

/** Webhook未設定のローカル開発環境向け。何もしない(未配信として返す)。 */
class NoopFamilyNotifier : FamilyNotifier {
    override suspend fun notify(event: Event, deviceName: String): Boolean = false
}

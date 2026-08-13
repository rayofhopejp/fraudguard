package com.fraudguard.server.tools

import com.fraudguard.server.domain.model.Event
import com.fraudguard.server.domain.model.EventMetadata
import com.fraudguard.server.domain.model.EventType
import com.fraudguard.server.domain.model.RiskLevel
import com.fraudguard.server.notify.FamilyNotifierProvider
import java.time.Instant
import kotlinx.coroutines.runBlocking

/**
 * 開発ツール: Slack通知の設定と見え方を、実機操作なしで確認する。
 * requirements.md 2.2章の通知経路が正しく設定されているかの疎通確認用。
 *
 * 実行例:
 *   SLACK_WEBHOOK_URL="$(cat server/secrets/slack-webhook-url)" ./gradlew sendTestNotification
 */
fun main() {
    val webhookUrl = System.getenv("SLACK_WEBHOOK_URL").orEmpty()
    if (webhookUrl.isBlank()) {
        println("SLACK_WEBHOOK_URL が未設定です。通知は送信されません。")
        return
    }
    FamilyNotifierProvider.init(webhookUrl)

    runBlocking {
        // requirements.md 31章シナリオD相当の、実際に起こりうるCRITICAL警告を模したサンプル。
        val delivered = FamilyNotifierProvider.get().notify(
            Event(
                eventId = "test-notification",
                deviceId = "test-device",
                type = EventType.CORRELATED_RISK,
                riskLevel = RiskLevel.CRITICAL,
                title = "遠隔操作詐欺の可能性が非常に高い",
                detail = "未登録番号との通話の後、遠隔操作アプリがインストールされ直後に起動しました。",
                timestamp = Instant.now().toString(),
                metadata = EventMetadata(
                    phoneNumber = "+819012345678",
                    appName = "AnyDesk",
                    reason = "通話 → 遠隔操作アプリ導入 → 直後起動 の連鎖を検出",
                ),
            ),
            deviceName = "母のスマホ(テスト送信)",
        )
        if (delivered) {
            println("Slackへテスト通知を送信しました。チャンネルを確認してください。")
        } else {
            println("Slackへの送信に失敗しました。上のエラーログを確認してください。")
        }
    }
}

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
        // requirements.md 31章シナリオF相当の、SMS本文を含むWARNING警告を模したサンプル。
        val delivered = FamilyNotifierProvider.get().notify(
            Event(
                eventId = "test-notification",
                deviceId = "test-device",
                type = EventType.SMS_RECEIVED,
                riskLevel = RiskLevel.WARNING,
                title = "SMSを受信しました",
                detail = "監視端末がSMSを受信しました。",
                timestamp = Instant.now().toString(),
                metadata = EventMetadata(
                    phoneNumber = "+819012345678",
                    messageBody = "【還付金のお知らせ】お近くのATMへ行き、暗証番号を入力して振り込み手続きを完了してください。",
                    reason = "詐欺でよく使われる語句を含むSMSを受信しました(ATM、還付金、振り込み、暗証番号)",
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

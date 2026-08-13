package com.fraudguard.server.tools

import com.fraudguard.server.domain.model.Event
import com.fraudguard.server.domain.model.EventMetadata
import com.fraudguard.server.domain.model.EventType
import com.fraudguard.server.domain.model.RiskLevel
import com.fraudguard.server.notify.FamilyNotifierProvider
import com.fraudguard.server.security.CommandKeys
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * 開発ツール: Slack通知の設定と見え方を、実機操作なしで確認する。
 * requirements.md 2.2章の通知経路が正しく設定されているかの疎通確認用。
 *
 * 引数にdeviceIdを渡すと、requirements.md 10.3章の
 * 「家族の通話としてマークする」リンク付きのアプリ内通話イベントを送る。
 * リンクは実在する端末を指す必要がある(マーク時にmonitored_devicesを参照するため)。
 *
 * 実行例:
 *   SLACK_WEBHOOK_URL=... ./gradlew sendTestNotification
 *   SLACK_WEBHOOK_URL=... PUBLIC_BASE_URL=... COMMAND_SIGNING_PRIVATE_KEY_PATH=... \
 *     ./gradlew sendTestNotification --args="<deviceId>"
 */
fun main(args: Array<String>) {
    val webhookUrl = System.getenv("SLACK_WEBHOOK_URL").orEmpty()
    if (webhookUrl.isBlank()) {
        println("SLACK_WEBHOOK_URL が未設定です。通知は送信されません。")
        return
    }
    val publicBaseUrl = System.getenv("PUBLIC_BASE_URL").orEmpty()
    FamilyNotifierProvider.init(webhookUrl, publicBaseUrl)

    val deviceId = args.getOrNull(0)
    val event = if (deviceId != null) callEvent(deviceId, publicBaseUrl) else smsEvent()

    runBlocking {
        val delivered = FamilyNotifierProvider.get().notify(event, deviceName = "監視端末(テスト送信)")
        if (delivered) {
            println("Slackへテスト通知を送信しました。チャンネルを確認してください。")
            if (deviceId != null) println("callId: ${event.metadata.callId}")
        } else {
            println("Slackへの送信に失敗しました。上のエラーログを確認してください。")
        }
    }
}

/** requirements.md 10.3章: マークリンク付きのアプリ内通話イベント。リンクの生成には署名鍵が要る。 */
private fun callEvent(deviceId: String, publicBaseUrl: String): Event {
    require(publicBaseUrl.isNotBlank()) { "PUBLIC_BASE_URL is required to include the mark-as-family-call link" }
    CommandKeys.init(
        System.getenv("COMMAND_SIGNING_PRIVATE_KEY_PATH")
            ?: error("COMMAND_SIGNING_PRIVATE_KEY_PATH is required to sign the mark link"),
    )
    return Event(
        eventId = UUID.randomUUID().toString(),
        deviceId = deviceId,
        type = EventType.CALL_INCOMING,
        riskLevel = RiskLevel.WARNING,
        title = "着信",
        detail = "アプリ内通話を検知しました。",
        timestamp = Instant.now().toString(),
        metadata = EventMetadata(
            callId = UUID.randomUUID().toString(),
            direction = "INCOMING",
            sourceApp = "jp.naver.line.android",
            reason = "LINEのアプリ内通話です(相手は特定できません)",
        ),
    )
}

private fun smsEvent(): Event = Event(
    eventId = "test-notification",
    deviceId = "test-device",
    type = EventType.SMS_RECEIVED,
    riskLevel = RiskLevel.WARNING,
    title = "SMSを受信しました",
    detail = "監視端末がSMSを受信しました。",
    timestamp = Instant.now().toString(),
    // requirements.md 31章シナリオF相当の、SMS本文を含むWARNING警告を模したサンプル。
    metadata = EventMetadata(
        phoneNumber = "+819012345678",
        messageBody = "【還付金のお知らせ】お近くのATMへ行き、暗証番号を入力して振り込み手続きを完了してください。",
        reason = "詐欺でよく使われる語句を含むSMSを受信しました(ATM、還付金、振り込み、暗証番号)",
    ),
)

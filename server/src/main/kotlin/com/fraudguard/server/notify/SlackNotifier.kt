package com.fraudguard.server.notify

import com.fraudguard.server.domain.model.Event
import com.fraudguard.server.domain.model.EventType
import com.fraudguard.server.domain.model.RiskLevel
import com.fraudguard.server.security.CallMarkToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** requirements.md 2.2章: Slack Incoming Webhookへのリスクイベント通知。 */
class SlackNotifier(
    private val webhookUrl: String,
    /** requirements.md 10.3章: 「家族の通話としてマーク」リンクの生成に使う。空ならリンクを載せない。 */
    private val publicBaseUrl: String = "",
) : FamilyNotifier {
    private companion object {
        const val MAX_SMS_BODY_CHARS = 500

        val CALL_EVENT_TYPES = setOf(
            EventType.CALL_INCOMING,
            EventType.CALL_OUTGOING,
            EventType.CALL_LONG_DURATION,
        )
    }

    private val logger = LoggerFactory.getLogger(SlackNotifier::class.java)
    private val client = HttpClient(CIO)
    private val json = Json { encodeDefaults = true }

    override suspend fun notify(event: Event, deviceName: String): Boolean {
        val message = SlackMessage(
            text = "[${event.riskLevel}] $deviceName: ${event.title}",
            attachments = listOf(
                SlackAttachment(
                    color = colorFor(event.riskLevel),
                    title = event.title,
                    text = event.detail,
                    fields = buildFields(event),
                ),
            ),
        )
        // 通知経路が黙って壊れるのは詐欺検知として致命的なため、必ず結果を確認してログに残す
        // (WebhookのrevokeやSlack側の障害に気づけないと、家族は「警告が来ていない=平常」と誤解する)。
        return try {
            val response = client.post(webhookUrl) {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(SlackMessage.serializer(), message))
            }
            if (!response.status.isSuccess()) {
                // requirements.md 25章: WebhookのURL自体が認証情報のため、URLも本文もログに出さない。
                logger.error("Slack notification failed: status=${response.status.value} body=${response.bodyAsText()}")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            logger.error("Slack notification failed to send: ${e.message}")
            false
        }
    }

    /**
     * 連結された長文SMSでSlackのペイロード上限に当たって通知そのものが落ちないよう、
     * 実用上十分な長さで切り詰める(全文はFamily Webのイベント詳細で確認できる)。
     */
    private fun truncateForSlack(text: String): String =
        if (text.length <= MAX_SMS_BODY_CHARS) text else text.take(MAX_SMS_BODY_CHARS) + "…(以下略)"

    /**
     * requirements.md 10.3章: 「これは家族の通話」とマークするためのリンク。
     *
     * LINE等のアプリ内通話は相手を電話番号で識別できずホワイトリストが使えないため、
     * 通知を受けた家族がその場で個別に止められる導線をここに用意する。
     * 通話に紐づくイベントにのみ付ける(アプリインストール等には意味がない)。
     */
    private fun markAsFamilyCallLink(event: Event): String? {
        if (publicBaseUrl.isBlank()) return null
        if (event.type !in CALL_EVENT_TYPES) return null
        val callId = event.metadata.callId ?: return null
        val token = runCatching { CallMarkToken.issue(event.deviceId, callId) }.getOrNull() ?: return null
        return "${publicBaseUrl.trimEnd('/')}/calls/mark?token=$token"
    }

    private fun colorFor(level: RiskLevel): String = when (level) {
        RiskLevel.CRITICAL -> "#C62828"
        RiskLevel.WARNING -> "#F9A825"
        RiskLevel.NOTICE -> "#4A8F5C"
        RiskLevel.INFO -> "#5B7A9D"
    }

    private fun buildFields(event: Event): List<SlackField> {
        val fields = mutableListOf<SlackField>()
        event.metadata.phoneNumber?.let { fields += SlackField("相手番号", it) }
        event.metadata.durationSeconds?.let { fields += SlackField("通話時間", "${it}秒") }
        event.metadata.appName?.let { fields += SlackField("アプリ", it) }
        event.metadata.sourceApp?.let { fields += SlackField("発生元アプリ", it) }
        // requirements.md 10.3章: 通知からの推定は確実性が低いため、信頼度も併せて示す。
        event.metadata.confidence?.let { fields += SlackField("信頼度", "%.0f%%".format(it * 100)) }
        // requirements.md 9章, 16.3章[v3]: SMS本文を含める。家族は本文を読まないと詐欺かどうか
        // 判断できないため、家族用の非公開Slackチャンネルに限り本文の掲載を許容する
        // (ロック画面プレビューからの覗き見リスクは残るという判断込み)。
        event.metadata.messageBody?.let {
            val label = if (event.type == EventType.NOTIFICATION_OBSERVED) "通知本文" else "SMS本文"
            fields += SlackField(label, truncateForSlack(it), short = false)
        }
        // requirements.md 17章: 「判定理由」を家族に伝える。
        event.metadata.reason?.let { fields += SlackField("判定理由", it, short = false) }
        fields += SlackField("発生時刻", event.timestamp)
        markAsFamilyCallLink(event)?.let {
            fields += SlackField("家族の通話なら", "<$it|この通話の通知を止める>", short = false)
        }
        return fields
    }
}

@Serializable
private data class SlackMessage(val text: String, val attachments: List<SlackAttachment> = emptyList())

@Serializable
private data class SlackAttachment(
    val color: String,
    val title: String,
    val text: String,
    val fields: List<SlackField> = emptyList(),
)

@Serializable
private data class SlackField(val title: String, val value: String, val short: Boolean = true)

package com.fraudguard.server.notify

import com.fraudguard.server.domain.model.Event
import com.fraudguard.server.domain.model.RiskLevel
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** requirements.md 2.2章: Slack Incoming Webhookへのリスクイベント通知。 */
class SlackNotifier(private val webhookUrl: String) : FamilyNotifier {
    private val client = HttpClient(CIO)
    private val json = Json { encodeDefaults = true }

    override suspend fun notify(event: Event, deviceName: String) {
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
        client.post(webhookUrl) {
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(SlackMessage.serializer(), message))
        }
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
        // requirements.md 17章: 「判定理由」を家族に伝える。
        event.metadata.reason?.let { fields += SlackField("判定理由", it, short = false) }
        fields += SlackField("発生時刻", event.timestamp)
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

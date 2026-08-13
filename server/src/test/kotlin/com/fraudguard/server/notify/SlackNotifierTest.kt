package com.fraudguard.server.notify

import com.fraudguard.server.domain.model.Event
import com.fraudguard.server.domain.model.EventMetadata
import com.fraudguard.server.domain.model.EventType
import com.fraudguard.server.domain.model.RiskLevel
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** SlackNotifierが実際にHTTP POSTでWebhookへ正しい形式のJSONを送信するかを、ローカルの偽Webhookサーバーで検証する。 */
class SlackNotifierTest {

    @Test
    fun `posts a Slack-formatted JSON payload to the webhook URL`() = runBlocking {
        val received = CompletableDeferred<String>()
        val port = 18089

        val server = embeddedServer(Netty, port = port) {
            routing {
                post("/webhook") {
                    received.complete(call.receiveText())
                    call.respond(HttpStatusCode.OK, "ok")
                }
            }
        }.start(wait = false)

        try {
            val notifier = SlackNotifier("http://localhost:$port/webhook")
            val event = Event(
                eventId = "evt-1",
                deviceId = "device-1",
                type = EventType.CALL_OUTGOING,
                riskLevel = RiskLevel.CRITICAL,
                title = "海外番号へ発信しました",
                detail = "未登録の海外番号へ発信しました。",
                timestamp = "2026-08-13T12:00:00Z",
                metadata = EventMetadata(phoneNumber = "+14155552671", direction = "OUTGOING"),
            )

            notifier.notify(event, "母のスマホ")

            val body = withTimeout(5000) { received.await() }
            assertTrue(body.contains("海外番号へ発信しました"), "本文にイベントタイトルが含まれること")
            assertTrue(body.contains("+14155552671"), "本文に電話番号が含まれること")
            assertTrue(body.contains("CRITICAL"), "本文にリスクレベルが含まれること")
            assertTrue(body.contains("#C62828"), "CRITICALの色コードが含まれること")
        } finally {
            server.stop(500, 1000)
        }
    }
}

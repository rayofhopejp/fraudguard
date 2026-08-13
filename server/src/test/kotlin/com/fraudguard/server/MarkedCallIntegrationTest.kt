package com.fraudguard.server

import com.fraudguard.server.db.DatabaseFactory
import com.fraudguard.server.db.repository.DeviceRepository
import com.fraudguard.server.db.repository.EventRepository
import com.fraudguard.server.db.repository.FamilyUserRepository
import com.fraudguard.server.db.repository.MarkedCallRepository
import com.fraudguard.server.db.repository.RiskEvaluationService
import com.fraudguard.server.domain.model.CreateEventRequest
import com.fraudguard.server.domain.model.EventMetadata
import com.fraudguard.server.domain.model.EventType
import com.fraudguard.server.domain.model.RiskLevel
import com.fraudguard.server.security.CallMarkToken
import com.fraudguard.server.security.CommandKeys
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * requirements.md 10.3章: LINE等のアプリ内通話は既定で通知し、家族が「家族の通話」とマークしたら
 * その通話について以降は通知しない、という一連の流れを実DBに対して検証する。
 *
 * IntegrationSmokeTestと同じく TEST_DATABASE_URL / TEST_COMMAND_SIGNING_KEY が未設定ならスキップする。
 */
class MarkedCallIntegrationTest {

    @Test
    fun `app call is notified by default and silenced once marked as a family call`() {
        val dbUrl = System.getenv("TEST_DATABASE_URL") ?: return
        val pemPath = System.getenv("TEST_COMMAND_SIGNING_KEY") ?: return

        DatabaseFactory.init(
            jdbcUrl = dbUrl,
            user = System.getenv("TEST_DATABASE_USER") ?: "fraudguard",
            password = System.getenv("TEST_DATABASE_PASSWORD") ?: "fraudguard",
        )
        CommandKeys.init(pemPath)

        runBlocking {
            val family = FamilyUserRepository.resolveOrCreate(
                "marked-call-sub-${UUID.randomUUID()}",
                "marked-call-${UUID.randomUUID()}@example.invalid",
            )
            val deviceId = DeviceRepository.createDeviceWithOwner("マークテスト端末", family.familyUserId)
            val callId = UUID.randomUUID().toString()

            // 1. LINE通話の着信。番号を持たないが既定では通知対象(WARNING)になること。
            ingestAppCall(deviceId, callId, EventType.CALL_INCOMING, durationSeconds = null)
            val incoming = EventRepository.listForDevice(deviceId).single { it.type == EventType.CALL_INCOMING }
            assertEquals(RiskLevel.WARNING, incoming.riskLevel, "アプリ内通話は既定で通知されること")
            assertTrue(
                incoming.metadata.reason?.contains("LINE") == true,
                "「番号の形式が不正」ではなくアプリ内通話として判定されること",
            )

            // 2. 通知に載るリンクのトークンが、この端末・この通話を指していること。
            val token = CallMarkToken.issue(deviceId, callId)
            val payload = assertNotNull(CallMarkToken.verify(token))
            assertEquals(deviceId, payload.deviceId)
            assertEquals(callId, payload.callId)
            assertNull(CallMarkToken.verify(token + "x"), "改竄されたトークンは通らないこと")

            // 3. 家族がマークする(Slackのリンク経由なので操作者は不明)。
            assertTrue(MarkedCallRepository.mark(deviceId, callId, markedBy = null))
            assertTrue(!MarkedCallRepository.mark(deviceId, callId, markedBy = null), "二重マークは冪等であること")

            // 4. 同じ通話の通話時間イベントは通知閾値を下回ること。
            ingestAppCall(deviceId, callId, EventType.CALL_LONG_DURATION, durationSeconds = 600)
            val duration = EventRepository.listForDevice(deviceId).single { it.type == EventType.CALL_LONG_DURATION }
            assertEquals(RiskLevel.INFO, duration.riskLevel, "マーク済みの通話は以降通知されないこと")

            // 5. マークは通話単位。別の通話は通知されたままであること。
            val otherCallId = UUID.randomUUID().toString()
            ingestAppCall(deviceId, otherCallId, EventType.CALL_LONG_DURATION, durationSeconds = 600)
            val otherDuration = EventRepository.listForDevice(deviceId)
                .single { it.type == EventType.CALL_LONG_DURATION && it.metadata.callId == otherCallId }
            assertEquals(RiskLevel.WARNING, otherDuration.riskLevel, "別の通話まで黙らせないこと")
        }
    }

    private suspend fun ingestAppCall(deviceId: String, callId: String, type: EventType, durationSeconds: Long?) {
        RiskEvaluationService.ingestEvent(
            CreateEventRequest(
                eventId = UUID.randomUUID().toString(),
                deviceId = deviceId,
                type = type,
                riskLevel = RiskLevel.NOTICE,
                title = "アプリ内通話",
                detail = "",
                timestamp = Instant.now().toString(),
                metadata = EventMetadata(
                    callId = callId,
                    direction = "INCOMING",
                    durationSeconds = durationSeconds,
                    sourceApp = "jp.naver.line.android",
                ),
            ),
            deviceId,
        )
    }
}

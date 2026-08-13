package com.fraudguard.server

import com.fraudguard.server.db.DatabaseFactory
import com.fraudguard.server.db.repository.CommandRepository
import com.fraudguard.server.db.repository.DeviceAuthRepository
import com.fraudguard.server.db.repository.DeviceHealthService
import com.fraudguard.server.db.repository.DeviceRepository
import com.fraudguard.server.db.repository.EventRepository
import com.fraudguard.server.db.repository.FamilyRepository
import com.fraudguard.server.db.repository.FamilyUserRepository
import com.fraudguard.server.db.repository.HeartbeatRepository
import com.fraudguard.server.db.repository.HeartbeatWatchdog
import com.fraudguard.server.db.repository.RiskEvaluationService
import com.fraudguard.server.db.repository.WhitelistRepository
import com.fraudguard.server.domain.model.CommandType
import com.fraudguard.server.domain.model.CreateEventRequest
import com.fraudguard.server.domain.model.EventMetadata
import com.fraudguard.server.domain.model.EventType
import com.fraudguard.server.domain.model.RiskLevel
import com.fraudguard.server.security.CommandKeys
import com.fraudguard.server.security.CommandSigner
import com.fraudguard.server.security.canonicalCommandPayload
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters

/**
 * 実際のPostgreSQLに対してリポジトリ層を通しで検証する統合テスト。
 * TEST_DATABASE_URL / TEST_COMMAND_SIGNING_KEY が未設定の環境(通常のCI等)ではスキップする。
 *
 * ローカルでの実行例:
 *   docker run -d -e POSTGRES_DB=fraudguard -e POSTGRES_USER=fraudguard -e POSTGRES_PASSWORD=fraudguard -p 5432:5432 postgres:16
 *   openssl genpkey -algorithm ed25519 -out /tmp/test-key.pem
 *   TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/fraudguard TEST_COMMAND_SIGNING_KEY=/tmp/test-key.pem ./gradlew test
 */
class IntegrationSmokeTest {

    @Test
    fun `pairing, whitelist, event, command and heartbeat flows work end to end`() {
        val dbUrl = System.getenv("TEST_DATABASE_URL") ?: return
        val pemPath = System.getenv("TEST_COMMAND_SIGNING_KEY") ?: return

        DatabaseFactory.init(
            jdbcUrl = dbUrl,
            user = System.getenv("TEST_DATABASE_USER") ?: "fraudguard",
            password = System.getenv("TEST_DATABASE_PASSWORD") ?: "fraudguard",
        )
        CommandKeys.init(pemPath)

        runBlocking {
            // 1. 家族ユーザーの自動プロビジョニング(requirements.md 25章)
            val family = FamilyUserRepository.resolveOrCreate("test-sub-${UUID.randomUUID()}", "test-${UUID.randomUUID()}@example.com")
            assertNotNull(family.familyUserId)

            // 2. 端末登録 + ペアリングコード発行 + 交換(requirements.md 34章)
            val deviceId = DeviceRepository.createDeviceWithOwner("テスト端末", family.familyUserId)
            assertTrue(DeviceRepository.isMember(deviceId, family.familyUserId))

            val code = DeviceRepository.issuePairingCode(deviceId)
            val pairingResult = DeviceRepository.exchangePairingCode(code)
            assertNotNull(pairingResult)
            assertEquals(deviceId, pairingResult.deviceId)

            // 同一コードの二重使用は失敗すること
            assertNull(DeviceRepository.exchangePairingCode(code))

            // 3. device-auth: 発行したAPIキーで認証できること(requirements.md 25章)
            val devicePrincipal = DeviceAuthRepository.validate(pairingResult.apiKey)
            assertEquals(deviceId, devicePrincipal?.deviceId)

            // 4. ホワイトリストCRUD(requirements.md 6章)
            val entry = WhitelistRepository.add(deviceId, "+819012345678", "母", null, family.familyUserId)
            assertEquals(1, WhitelistRepository.list(deviceId).size)
            assertTrue(WhitelistRepository.delete(deviceId, entry.entryId))
            assertEquals(0, WhitelistRepository.list(deviceId).size)

            // 5. イベント登録(eventIdの冪等性、requirements.md 24章)と確認済み操作(18章)
            val eventId = UUID.randomUUID().toString()
            val request = CreateEventRequest(
                eventId = eventId,
                deviceId = deviceId,
                type = EventType.CALL_LONG_DURATION,
                riskLevel = RiskLevel.WARNING,
                title = "テスト",
                detail = "詳細",
                timestamp = Instant.now().toString(),
                metadata = EventMetadata(phoneNumber = "+819012345678", durationSeconds = 200),
            )
            EventRepository.insertIfAbsent(request, deviceId)
            EventRepository.insertIfAbsent(request, deviceId) // 同じeventIdを再送しても増えないこと
            assertEquals(1, EventRepository.listForDevice(deviceId).size)
            assertTrue(EventRepository.markAcknowledged(eventId, family.familyUserId))
            assertTrue(EventRepository.find(eventId)!!.acknowledged)

            // 6. 遠隔コマンドの発行・署名検証・ポーリング・実行報告(requirements.md 8章)
            val command = CommandRepository.create(deviceId, "call-1", CommandType.DISCONNECT_CALL, family.familyUserId)
            val payload = canonicalCommandPayload(
                commandId = command.commandId,
                deviceId = command.deviceId,
                callId = command.callId,
                type = command.type.name,
                issuedAt = command.issuedAt,
                expiresAt = command.expiresAt,
                nonce = command.nonce,
            )
            val publicKeyBytes = Base64.getDecoder().decode(pairingResult.serverPublicKey)
            val verified = CommandSigner.verify(Ed25519PublicKeyParameters(publicKeyBytes, 0), payload, command.signature)
            assertTrue(verified, "サーバーが発行した署名が公開鍵で検証できること")

            assertEquals(1, CommandRepository.listPending(deviceId).size)
            // 実運用ではGET /commands/pendingのルートハンドラがmarkDeliveredを呼ぶ(routes/CommandRoutes.kt)。
            // ここではルートを経由しないため、同じ副作用を明示的に再現しておく。
            CommandRepository.markDelivered(command.commandId)
            assertTrue(CommandRepository.reportExecution(command.commandId, deviceId, true, null, Instant.now()))

            // 7. ハートビート記録(requirements.md 35章[v2])
            HeartbeatRepository.record(deviceId, notificationListenerEnabled = true, roleDialerHeld = false, appVersion = "0.1.0")
            val devices = DeviceRepository.listForFamilyUser(family.familyUserId)
            assertEquals(1, devices.size)
            assertNotNull(devices.first().lastHeartbeatAt)

            // 8. 家族メンバー一覧・Push token登録(requirements.md 16.2章)
            val members = FamilyRepository.listRelatedMembers(family.familyUserId)
            assertTrue(members.any { it.familyUserId == family.familyUserId })
            FamilyRepository.registerPushToken(family.familyUserId, "dummy-fcm-token", "android")

            // 9. 端末側のFCMトークン登録(requirements.md 8章[v2])
            assertNull(DeviceRepository.findFcmToken(deviceId))
            DeviceRepository.updateFcmToken(deviceId, "monitor-fcm-token")
            assertEquals("monitor-fcm-token", DeviceRepository.findFcmToken(deviceId))

            // FCM未設定(デフォルトのNoopFcmClient)でもコマンド発行自体は成立し、delivered=falseのままpendingに残ること。
            val secondCommand = CommandRepository.create(deviceId, "call-2", CommandType.DISCONNECT_CALL, family.familyUserId)
            val fcmToken = DeviceRepository.findFcmToken(deviceId)
            assertNotNull(fcmToken)
            val sent = com.fraudguard.server.push.FcmClientProvider.get().sendCommandDataMessage(fcmToken, secondCommand)
            assertEquals(false, sent, "サービスアカウント未設定時はNoopFcmClientが常に失敗を返すこと")
            assertEquals(1, CommandRepository.listPending(deviceId).size, "未配信のコマンドはpendingポーリングで拾えること")
        }
    }

    @Test
    fun `RiskEvaluationService overrides risk level and detects correlated remote-control pattern`() {
        val dbUrl = System.getenv("TEST_DATABASE_URL") ?: return
        val pemPath = System.getenv("TEST_COMMAND_SIGNING_KEY") ?: return

        DatabaseFactory.init(
            jdbcUrl = dbUrl,
            user = System.getenv("TEST_DATABASE_USER") ?: "fraudguard",
            password = System.getenv("TEST_DATABASE_PASSWORD") ?: "fraudguard",
        )
        CommandKeys.init(pemPath)

        runBlocking {
            val family = FamilyUserRepository.resolveOrCreate("test-sub-${UUID.randomUUID()}", "test-${UUID.randomUUID()}@example.com")
            val deviceId = DeviceRepository.createDeviceWithOwner("テスト端末2", family.familyUserId)

            // requirements.md 7.2章: 端末側がINFOと誤って報告しても、サーバー側で海外発信をCRITICALへ上書きすること。
            val outgoingForeignCallEventId = UUID.randomUUID().toString()
            RiskEvaluationService.ingestEvent(
                CreateEventRequest(
                    eventId = outgoingForeignCallEventId,
                    deviceId = deviceId,
                    type = EventType.CALL_OUTGOING,
                    riskLevel = RiskLevel.INFO, // 端末側の誤判定を想定
                    title = "発信",
                    detail = "",
                    timestamp = Instant.now().toString(),
                    metadata = EventMetadata(phoneNumber = "+14155552671", direction = "OUTGOING"),
                ),
                deviceId,
            )
            assertEquals(RiskLevel.CRITICAL, EventRepository.find(outgoingForeignCallEventId)?.riskLevel)

            // requirements.md 6.2章: ホワイトリスト登録済み番号なら、端末側がWARNINGと報告してもINFOへ引き下げること。
            WhitelistRepository.add(deviceId, "+819011112222", "病院", null, family.familyUserId)
            val whitelistedCallEventId = UUID.randomUUID().toString()
            RiskEvaluationService.ingestEvent(
                CreateEventRequest(
                    eventId = whitelistedCallEventId,
                    deviceId = deviceId,
                    type = EventType.CALL_INCOMING,
                    riskLevel = RiskLevel.WARNING,
                    title = "着信",
                    detail = "",
                    timestamp = Instant.now().toString(),
                    metadata = EventMetadata(phoneNumber = "+819011112222", direction = "INCOMING"),
                ),
                deviceId,
            )
            assertEquals(RiskLevel.INFO, EventRepository.find(whitelistedCallEventId)?.riskLevel)

            // requirements.md 14.1章: 不審な通話 → 遠隔操作アプリ導入 → 直後起動、でCORRELATED_RISKが生成されること。
            RiskEvaluationService.ingestEvent(
                CreateEventRequest(
                    eventId = UUID.randomUUID().toString(),
                    deviceId = deviceId,
                    type = EventType.CALL_INCOMING,
                    riskLevel = RiskLevel.NOTICE,
                    title = "着信",
                    detail = "",
                    timestamp = Instant.now().toString(),
                    metadata = EventMetadata(phoneNumber = "09099998888", direction = "INCOMING"),
                ),
                deviceId,
            )
            RiskEvaluationService.ingestEvent(
                CreateEventRequest(
                    eventId = UUID.randomUUID().toString(),
                    deviceId = deviceId,
                    type = EventType.APP_REMOTE_CONTROL_INSTALLED,
                    riskLevel = RiskLevel.CRITICAL,
                    title = "遠隔操作アプリ導入",
                    detail = "",
                    timestamp = Instant.now().toString(),
                    metadata = EventMetadata(packageName = "com.anydesk.anydeskandroid", appName = "AnyDesk"),
                ),
                deviceId,
            )
            RiskEvaluationService.ingestEvent(
                CreateEventRequest(
                    eventId = UUID.randomUUID().toString(),
                    deviceId = deviceId,
                    type = EventType.APP_LAUNCHED_AFTER_INSTALL,
                    riskLevel = RiskLevel.INFO,
                    title = "初回起動",
                    detail = "",
                    timestamp = Instant.now().toString(),
                    metadata = EventMetadata(packageName = "com.anydesk.anydeskandroid"),
                ),
                deviceId,
            )

            val events = EventRepository.listForDevice(deviceId)
            val correlated = events.filter { it.type == EventType.CORRELATED_RISK }
            assertEquals(1, correlated.size, "相関イベントが1件生成されること")
            assertEquals(RiskLevel.CRITICAL, correlated.first().riskLevel)

            // requirements.md 30章: 同じ相関イベントを重複生成しないこと(直近の抑制期間内)。
            RiskEvaluationService.ingestEvent(
                CreateEventRequest(
                    eventId = UUID.randomUUID().toString(),
                    deviceId = deviceId,
                    type = EventType.APP_LAUNCHED_AFTER_INSTALL,
                    riskLevel = RiskLevel.INFO,
                    title = "再起動",
                    detail = "",
                    timestamp = Instant.now().toString(),
                    metadata = EventMetadata(packageName = "com.anydesk.anydeskandroid"),
                ),
                deviceId,
            )
            val correlatedAfterRepeat = EventRepository.listForDevice(deviceId).filter { it.type == EventType.CORRELATED_RISK }
            assertEquals(1, correlatedAfterRepeat.size, "抑制期間内は相関イベントが再生成されないこと")
        }
    }

    @Test
    fun `heartbeat permission revocation and watchdog timeout generate deduped device_health events`() {
        val dbUrl = System.getenv("TEST_DATABASE_URL") ?: return
        val pemPath = System.getenv("TEST_COMMAND_SIGNING_KEY") ?: return

        DatabaseFactory.init(
            jdbcUrl = dbUrl,
            user = System.getenv("TEST_DATABASE_USER") ?: "fraudguard",
            password = System.getenv("TEST_DATABASE_PASSWORD") ?: "fraudguard",
        )
        CommandKeys.init(pemPath)

        runBlocking {
            val family = FamilyUserRepository.resolveOrCreate("test-sub-${UUID.randomUUID()}", "test-${UUID.randomUUID()}@example.com")

            // 1. requirements.md 35.3章: 通知アクセス失効の即時検知(ハートビート受信時点)。
            val deviceId = DeviceRepository.createDeviceWithOwner("テスト端末3", family.familyUserId)
            HeartbeatRepository.record(deviceId, notificationListenerEnabled = false, roleDialerHeld = false, appVersion = "0.1.0")
            DeviceHealthService.reportPermissionRevoked(deviceId)
            assertEquals(1, EventRepository.listForDevice(deviceId).count { it.type == EventType.DEVICE_HEALTH })

            // requirements.md 30章: 抑制期間内に繰り返し呼んでも重複生成されないこと。
            DeviceHealthService.reportPermissionRevoked(deviceId)
            assertEquals(
                1,
                EventRepository.listForDevice(deviceId).count { it.type == EventType.DEVICE_HEALTH },
                "抑制期間内はdevice_healthイベントが重複生成されないこと",
            )

            // 2. requirements.md 35.3, 35.4章: ハートビート途絶のタイムアウト検知(HeartbeatWatchdog)。
            //    実時間を待つ代わりに閾値を操作して「今作った端末を即座にstale扱いにする/しない」を検証する。
            val deviceId2 = DeviceRepository.createDeviceWithOwner("テスト端末4", family.familyUserId)
            val code2 = DeviceRepository.issuePairingCode(deviceId2)
            assertNotNull(DeviceRepository.exchangePairingCode(code2)) // ペアリング済みでないと検知対象にならない

            assertTrue(deviceId2 in DeviceRepository.findStaleDevices(thresholdMinutes = 0), "作成直後でも閾値0分なら即staleと判定されること")
            assertTrue(deviceId2 !in DeviceRepository.findStaleDevices(thresholdMinutes = 1440), "作成直後は24時間閾値ではstale扱いされないこと")

            HeartbeatWatchdog.checkOnce(timeoutMinutes = 0)
            assertEquals(1, EventRepository.listForDevice(deviceId2).count { it.type == EventType.DEVICE_HEALTH })

            // 抑制期間内の再チェックでは重複生成されないこと。
            HeartbeatWatchdog.checkOnce(timeoutMinutes = 0)
            assertEquals(
                1,
                EventRepository.listForDevice(deviceId2).count { it.type == EventType.DEVICE_HEALTH },
                "抑制期間内はウォッチドッグの再チェックでも重複生成されないこと",
            )
        }
    }
}

package com.fraudguard.server

import com.fraudguard.server.db.DatabaseFactory
import com.fraudguard.server.db.repository.DeviceAuthRepository
import com.fraudguard.server.db.repository.DeviceRepository
import com.fraudguard.server.db.repository.FamilyUserRepository
import com.fraudguard.server.security.CommandKeys
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * requirements.md 25章: 端末を紛失したとき、ペアリングの無効化だけで即座に遮断できること。
 *
 * 無効化が実際にAPIキーを弾かなければ、この機能はボタンがあるだけで何もしていないことになる。
 * 家族は「止めた」と思うのに端末は送り続ける、という最も危険な誤解を生むため実DBで確認する。
 */
class RevokePairingIntegrationTest {

    @Test
    fun `revoking a pairing blocks the device but keeps its history`() {
        val dbUrl = System.getenv("TEST_DATABASE_URL") ?: return
        val pemPath = System.getenv("TEST_COMMAND_SIGNING_KEY") ?: return

        DatabaseFactory.init(
            jdbcUrl = dbUrl,
            user = System.getenv("TEST_DATABASE_USER") ?: "fraudguard",
            password = System.getenv("TEST_DATABASE_PASSWORD") ?: "fraudguard",
        )
        CommandKeys.init(pemPath)

        runBlocking {
            val owner = FamilyUserRepository.resolveOrCreate("sub-${UUID.randomUUID()}", "revoke-${UUID.randomUUID()}@example.invalid")
            val deviceId = DeviceRepository.createDeviceWithOwner("停止テスト端末", owner.familyUserId)
            val code = DeviceRepository.issuePairingCode(deviceId)
            val paired = assertNotNull(DeviceRepository.exchangePairingCode(code))

            // 無効化する前は、そのAPIキーで端末として認証できる。
            assertEquals(deviceId, DeviceAuthRepository.validate(paired.apiKey)?.deviceId)
            assertNull(DeviceRepository.listForFamilyUser(owner.familyUserId).single { it.deviceId == deviceId }.revokedAt)

            DeviceRepository.revoke(deviceId)

            // 無効化後は同じキーが一切通らない。
            assertNull(DeviceAuthRepository.validate(paired.apiKey), "無効化したAPIキーは通らないこと")

            // 端末と履歴は残り、家族の一覧では「停止済み」と分かること。
            val listed = DeviceRepository.listForFamilyUser(owner.familyUserId).single { it.deviceId == deviceId }
            assertNotNull(listed.revokedAt, "一覧から停止済みだと分かること")
            assertTrue(DeviceRepository.isMember(deviceId, owner.familyUserId))
        }
    }
}

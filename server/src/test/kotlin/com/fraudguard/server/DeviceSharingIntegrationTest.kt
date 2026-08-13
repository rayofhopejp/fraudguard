package com.fraudguard.server

import com.fraudguard.server.db.DatabaseFactory
import com.fraudguard.server.db.repository.DeviceRepository
import com.fraudguard.server.db.repository.FamilyRepository
import com.fraudguard.server.db.repository.FamilyUserRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * requirements.md 16.2章: 1台の端末を複数の家族で見守る。
 *
 * 誰がどの端末を見られるかは、通話履歴とSMS本文の閲覧範囲そのものなので、
 * 「共有していない人には見えない」「所有者は外せない」を実DBで固定しておく。
 *
 * TEST_DATABASE_URL / TEST_COMMAND_SIGNING_KEY が未設定ならスキップする。
 */
class DeviceSharingIntegrationTest {

    @Test
    fun `a device can be shared with another family member and unshared again`() {
        val dbUrl = System.getenv("TEST_DATABASE_URL") ?: return
        System.getenv("TEST_COMMAND_SIGNING_KEY") ?: return

        DatabaseFactory.init(
            jdbcUrl = dbUrl,
            user = System.getenv("TEST_DATABASE_USER") ?: "fraudguard",
            password = System.getenv("TEST_DATABASE_PASSWORD") ?: "fraudguard",
        )

        runBlocking {
            val ownerEmail = "owner-${UUID.randomUUID()}@example.invalid"
            val otherEmail = "other-${UUID.randomUUID()}@example.invalid"
            val owner = FamilyUserRepository.resolveOrCreate("sub-${UUID.randomUUID()}", ownerEmail)
            val other = FamilyUserRepository.resolveOrCreate("sub-${UUID.randomUUID()}", otherEmail)
            val deviceId = DeviceRepository.createDeviceWithOwner("共有テスト端末", owner.familyUserId)

            // 共有前: 他人からは見えない。
            assertFalse(DeviceRepository.isMember(deviceId, other.familyUserId))
            assertTrue(DeviceRepository.listForFamilyUser(other.familyUserId).none { it.deviceId == deviceId })

            assertTrue(FamilyRepository.addMemberByEmail(deviceId, otherEmail))

            // 共有後: 端末一覧に出て、イベントも見られる(isMemberが権限判定の共通の入口)。
            assertTrue(DeviceRepository.isMember(deviceId, other.familyUserId))
            assertTrue(DeviceRepository.listForFamilyUser(other.familyUserId).any { it.deviceId == deviceId })

            val members = FamilyRepository.listDeviceMembers(deviceId)
            assertEquals(2, members.size)
            assertTrue(members.single { it.familyUserId == owner.familyUserId }.isOwner)
            assertFalse(members.single { it.familyUserId == other.familyUserId }.isOwner)

            // 二重追加は害が無いこと(ボタンを二度押しても壊れない)。
            assertTrue(FamilyRepository.addMemberByEmail(deviceId, otherEmail))
            assertEquals(2, FamilyRepository.listDeviceMembers(deviceId).size)

            // 所有者は外せない。外せると誰も管理できない端末が残る。
            assertFalse(FamilyRepository.removeMember(deviceId, owner.familyUserId))
            assertTrue(DeviceRepository.isMember(deviceId, owner.familyUserId))

            // 共有の解除後は再び見えなくなる。
            assertTrue(FamilyRepository.removeMember(deviceId, other.familyUserId))
            assertFalse(DeviceRepository.isMember(deviceId, other.familyUserId))
            assertTrue(DeviceRepository.listForFamilyUser(other.familyUserId).none { it.deviceId == deviceId })
        }
    }

    /** ログインしたことがない相手は家族ユーザーが存在しないため、追加できないこと。 */
    @Test
    fun `sharing with an address that has never signed in is refused`() {
        val dbUrl = System.getenv("TEST_DATABASE_URL") ?: return
        System.getenv("TEST_COMMAND_SIGNING_KEY") ?: return

        DatabaseFactory.init(
            jdbcUrl = dbUrl,
            user = System.getenv("TEST_DATABASE_USER") ?: "fraudguard",
            password = System.getenv("TEST_DATABASE_PASSWORD") ?: "fraudguard",
        )

        runBlocking {
            val owner = FamilyUserRepository.resolveOrCreate("sub-${UUID.randomUUID()}", "owner-${UUID.randomUUID()}@example.invalid")
            val deviceId = DeviceRepository.createDeviceWithOwner("共有テスト端末", owner.familyUserId)

            assertFalse(FamilyRepository.addMemberByEmail(deviceId, "never-logged-in@example.invalid"))
            assertEquals(1, FamilyRepository.listDeviceMembers(deviceId).size)
        }
    }
}

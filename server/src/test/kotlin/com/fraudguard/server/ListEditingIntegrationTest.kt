package com.fraudguard.server

import com.fraudguard.server.db.DatabaseFactory
import com.fraudguard.server.db.repository.BlacklistRepository
import com.fraudguard.server.db.repository.DeviceRepository
import com.fraudguard.server.db.repository.FamilyUserRepository
import com.fraudguard.server.db.repository.WhitelistRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * requirements.md 6章, 18章[v2]: 登録済みのホワイトリスト/ブラックリストを編集・削除できること。
 *
 * ホワイトリストは「この番号は安全」という判断そのもので、無効化や名前の修正が効かないと、
 * 一度の入力ミスを消して入れ直すしかなくなる。判定に直結するので実DBで確認する。
 */
class ListEditingIntegrationTest {

    private fun initDb(): Boolean {
        val dbUrl = System.getenv("TEST_DATABASE_URL") ?: return false
        DatabaseFactory.init(
            jdbcUrl = dbUrl,
            user = System.getenv("TEST_DATABASE_USER") ?: "fraudguard",
            password = System.getenv("TEST_DATABASE_PASSWORD") ?: "fraudguard",
        )
        return true
    }

    @Test
    fun `a whitelist entry can be renamed and disabled, which changes the judgement`() {
        if (!initDb()) return

        runBlocking {
            val owner = FamilyUserRepository.resolveOrCreate("sub-${UUID.randomUUID()}", "wl-${UUID.randomUUID()}@example.invalid")
            val deviceId = DeviceRepository.createDeviceWithOwner("編集テスト端末", owner.familyUserId)
            val number = "+8190${(10000000..99999999).random()}"

            val entry = WhitelistRepository.add(deviceId, number, "誤った名前", null, owner.familyUserId)
            assertTrue(WhitelistRepository.isWhitelisted(deviceId, number))

            val updated = WhitelistRepository.update(deviceId, entry.entryId, "娘", "携帯", enabled = true)
            assertEquals("娘", updated?.displayName)
            assertEquals("携帯", updated?.note)

            // 無効にすると、その番号はホワイトリスト扱いでなくなる(未登録番号として判定される)。
            WhitelistRepository.update(deviceId, entry.entryId, "娘", "携帯", enabled = false)
            assertFalse(WhitelistRepository.isWhitelisted(deviceId, number))

            // 他人の端末のエントリは触れない(deviceIdが一致しなければ更新できない)。
            val otherDeviceId = DeviceRepository.createDeviceWithOwner("別端末", owner.familyUserId)
            assertNull(WhitelistRepository.update(otherDeviceId, entry.entryId, "乗っ取り", null, enabled = true))
            assertEquals("娘", WhitelistRepository.list(deviceId).single { it.entryId == entry.entryId }.displayName)
        }
    }

    @Test
    fun `a blacklist entry can be edited and removed`() {
        if (!initDb()) return

        runBlocking {
            val owner = FamilyUserRepository.resolveOrCreate("sub-${UUID.randomUUID()}", "bl-${UUID.randomUUID()}@example.invalid")
            val deviceId = DeviceRepository.createDeviceWithOwner("編集テスト端末", owner.familyUserId)
            val number = "+8190${(10000000..99999999).random()}"

            val entry = BlacklistRepository.add(deviceId, number, "不審な電話", owner.familyUserId)
            assertTrue(BlacklistRepository.isBlacklisted(deviceId, number))

            assertEquals("詐欺の電話", BlacklistRepository.update(deviceId, entry.entryId, "詐欺の電話")?.reason)
            assertNull(BlacklistRepository.update(deviceId, entry.entryId, null)?.reason)

            // 他の端末からは触れないこと。
            val otherDeviceId = DeviceRepository.createDeviceWithOwner("別端末", owner.familyUserId)
            assertNull(BlacklistRepository.update(otherDeviceId, entry.entryId, "乗っ取り"))
            assertFalse(BlacklistRepository.delete(otherDeviceId, entry.entryId))
            assertTrue(BlacklistRepository.isBlacklisted(deviceId, number))

            assertTrue(BlacklistRepository.delete(deviceId, entry.entryId))
            assertFalse(BlacklistRepository.isBlacklisted(deviceId, number))
        }
    }
}

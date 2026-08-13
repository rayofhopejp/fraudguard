package com.fraudguard.server.db.repository

import com.fraudguard.server.db.dbQuery
import com.fraudguard.server.db.tables.DeviceMembers
import com.fraudguard.server.db.tables.FamilyUsers
import com.fraudguard.server.db.tables.PushDevices
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select

data class FamilyMemberDto(val familyUserId: String, val displayName: String, val email: String)

/** requirements.md 16.2章, 19章: 監視端末を共有する家族メンバー一覧、複数Push端末の登録。 */
object FamilyRepository {
    /** 呼び出し元が所属するいずれかの端末を、同じく共有している家族ユーザーの一覧(重複除去)。 */
    suspend fun listRelatedMembers(familyUserId: String): List<FamilyMemberDto> = dbQuery {
        val myDevices = DeviceMembers
            .slice(DeviceMembers.deviceId)
            .select { DeviceMembers.familyUserId eq familyUserId }
            .map { it[DeviceMembers.deviceId] }

        if (myDevices.isEmpty()) return@dbQuery emptyList()

        (DeviceMembers innerJoin FamilyUsers)
            .select { DeviceMembers.deviceId inList myDevices }
            .map {
                FamilyMemberDto(
                    familyUserId = it[FamilyUsers.id],
                    displayName = it[FamilyUsers.displayName],
                    email = it[FamilyUsers.email],
                )
            }
            .distinctBy { it.familyUserId }
    }

    suspend fun registerPushToken(familyUserId: String, fcmToken: String, platform: String) = dbQuery {
        PushDevices.insert {
            it[id] = UUID.randomUUID().toString()
            it[PushDevices.familyUserId] = familyUserId
            it[PushDevices.fcmToken] = fcmToken
            it[PushDevices.platform] = platform
            it[createdAt] = Instant.now()
        }
    }
}

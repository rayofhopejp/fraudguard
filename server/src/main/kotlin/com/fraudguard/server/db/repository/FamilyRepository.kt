package com.fraudguard.server.db.repository

import com.fraudguard.server.db.dbQuery
import com.fraudguard.server.db.tables.DeviceMembers
import com.fraudguard.server.db.tables.FamilyUsers
import com.fraudguard.server.db.tables.MonitoredDevices
import com.fraudguard.server.db.tables.PushDevices
import java.time.Instant
import kotlinx.serialization.Serializable
import java.util.UUID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select

@Serializable
data class FamilyMemberDto(val familyUserId: String, val displayName: String, val email: String)

/** requirements.md 16.2章: 端末を共有している家族と、その人が所有者かどうか。 */
@Serializable
data class DeviceMemberDto(
    val familyUserId: String,
    val displayName: String,
    val email: String,
    val isOwner: Boolean,
)

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

    /** requirements.md 16.2章: 指定した端末を共有している家族の一覧。 */
    suspend fun listDeviceMembers(deviceId: String): List<DeviceMemberDto> = dbQuery {
        val ownerId = MonitoredDevices
            .slice(MonitoredDevices.ownerFamilyUserId)
            .select { MonitoredDevices.id eq deviceId }
            .firstOrNull()?.get(MonitoredDevices.ownerFamilyUserId)

        (DeviceMembers innerJoin FamilyUsers)
            .select { DeviceMembers.deviceId eq deviceId }
            .map {
                DeviceMemberDto(
                    familyUserId = it[FamilyUsers.id],
                    displayName = it[FamilyUsers.displayName],
                    email = it[FamilyUsers.email],
                    isOwner = it[FamilyUsers.id] == ownerId,
                )
            }
    }

    /**
     * requirements.md 16.2章: 端末を別の家族と共有する。
     *
     * 相手はメールアドレスで指定する。ただし**相手が一度Family Webにログインしている必要がある**。
     * 家族ユーザーのレコードはCognitoの認証を経て初めて作られるため、
     * ログイン前の相手は、こちらからは存在しない人と区別がつかない。
     *
     * @return 追加できた場合true、そのメールアドレスの家族ユーザーが見つからない場合false。
     */
    suspend fun addMemberByEmail(deviceId: String, email: String): Boolean = dbQuery {
        val target = FamilyUsers
            .select { FamilyUsers.email eq email.trim().lowercase() }
            .firstOrNull() ?: return@dbQuery false

        val targetId = target[FamilyUsers.id]
        val already = DeviceMembers
            .select { (DeviceMembers.deviceId eq deviceId) and (DeviceMembers.familyUserId eq targetId) }
            .empty().not()
        if (already) return@dbQuery true

        DeviceMembers.insert {
            it[DeviceMembers.deviceId] = deviceId
            it[familyUserId] = targetId
            it[minRiskLevel] = "INFO"
            // 追加された家族も遠隔切断とホワイトリスト編集ができる。
            // 見守りは複数人で回すもので、通知を受けた人がその場で動けないと意味がない。
            it[canDisconnectCall] = true
            it[canEditWhitelist] = true
            it[createdAt] = Instant.now()
        }
        true
    }

    /** removeMemberが拒否した理由。画面に出す文言を分けるために区別する。 */
    enum class RemoveMemberResult { REMOVED, CANNOT_REMOVE_OWNER, NOT_ALLOWED }

    /**
     * requirements.md 16.2章: 共有を解除する。
     *
     * 誰でも誰でも外せる、にはしない。この一覧は通話履歴とSMS本文を見られる人の一覧であり、
     * 共有された人が他の家族を黙って外せると、見守りの目を減らす操作が誰にでもできてしまう。
     *
     *  - 端末を登録した人は、誰でも外せる
     *  - それ以外の人は、自分だけ外せる(見守りから抜ける)
     *  - 登録者は誰も外せない(外すと管理者のいない端末が残る)
     */
    suspend fun removeMember(
        deviceId: String,
        familyUserId: String,
        actorFamilyUserId: String,
    ): RemoveMemberResult = dbQuery {
        val ownerId = MonitoredDevices
            .slice(MonitoredDevices.ownerFamilyUserId)
            .select { MonitoredDevices.id eq deviceId }
            .firstOrNull()?.get(MonitoredDevices.ownerFamilyUserId)
        if (ownerId == familyUserId) return@dbQuery RemoveMemberResult.CANNOT_REMOVE_OWNER
        if (actorFamilyUserId != ownerId && actorFamilyUserId != familyUserId) {
            return@dbQuery RemoveMemberResult.NOT_ALLOWED
        }

        DeviceMembers.deleteWhere {
            it.run { (DeviceMembers.deviceId eq deviceId) and (DeviceMembers.familyUserId eq familyUserId) }
        }
        RemoveMemberResult.REMOVED
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

package com.fraudguard.server.db.repository

import com.fraudguard.server.db.dbQuery
import com.fraudguard.server.db.tables.DeviceHeartbeats
import com.fraudguard.server.db.tables.DeviceMembers
import com.fraudguard.server.db.tables.DevicePairings
import com.fraudguard.server.db.tables.MonitoredDevices
import com.fraudguard.server.db.tables.PairingCodes
import com.fraudguard.server.domain.model.DevicePairingResult
import com.fraudguard.server.domain.model.MonitoredDevice
import com.fraudguard.server.security.CommandKeys
import com.fraudguard.server.security.generateRandomToken
import com.fraudguard.server.security.sha256Hex
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

private const val PAIRING_CODE_TTL_MINUTES = 15L

object DeviceRepository {

    suspend fun isMember(deviceId: String, familyUserId: String): Boolean = dbQuery {
        DeviceMembers
            .select { (DeviceMembers.deviceId eq deviceId) and (DeviceMembers.familyUserId eq familyUserId) }
            .empty()
            .not()
    }

    /** requirements.md 17章: 通知に含める監視端末名の取得。 */
    suspend fun findName(deviceId: String): String? = dbQuery {
        MonitoredDevices.select { MonitoredDevices.id eq deviceId }.singleOrNull()?.get(MonitoredDevices.name)
    }

    suspend fun ownerOf(deviceId: String): String? = dbQuery {
        MonitoredDevices.select { MonitoredDevices.id eq deviceId }.singleOrNull()?.get(MonitoredDevices.ownerFamilyUserId)
    }

    /** requirements.md 8章[v2]: 遠隔コマンドのFCM即時送信先トークンの登録・更新(Monitorアプリが呼ぶ)。 */
    suspend fun updateFcmToken(deviceId: String, fcmToken: String) = dbQuery {
        MonitoredDevices.update({ MonitoredDevices.id eq deviceId }) {
            it[MonitoredDevices.fcmToken] = fcmToken
        }
    }

    suspend fun findFcmToken(deviceId: String): String? = dbQuery {
        MonitoredDevices.select { MonitoredDevices.id eq deviceId }.singleOrNull()?.get(MonitoredDevices.fcmToken)
    }

    /** requirements.md 16.3章: 家族ごとの「遠隔切断可能/不可」権限チェック。 */
    suspend fun canDisconnectCall(deviceId: String, familyUserId: String): Boolean = dbQuery {
        DeviceMembers
            .select { (DeviceMembers.deviceId eq deviceId) and (DeviceMembers.familyUserId eq familyUserId) }
            .singleOrNull()
            ?.get(DeviceMembers.canDisconnectCall)
            ?: false
    }

    /** requirements.md 21章: 家族ユーザーが所属する監視対象端末の一覧。 */
    suspend fun listForFamilyUser(familyUserId: String): List<MonitoredDevice> = dbQuery {
        val lastHeartbeat = DeviceHeartbeats.receivedAt.max()
        val heartbeats = DeviceHeartbeats
            .slice(DeviceHeartbeats.deviceId, lastHeartbeat)
            .selectAll()
            .groupBy(DeviceHeartbeats.deviceId)
            .associate { it[DeviceHeartbeats.deviceId] to it[lastHeartbeat] }

        val revokedAt = DevicePairings
            .slice(DevicePairings.deviceId, DevicePairings.revokedAt)
            .selectAll()
            .mapNotNull { row -> row[DevicePairings.revokedAt]?.let { row[DevicePairings.deviceId] to it } }
            .toMap()

        (MonitoredDevices innerJoin DeviceMembers)
            .select { DeviceMembers.familyUserId eq familyUserId }
            .map { row ->
                val deviceId = row[MonitoredDevices.id]
                MonitoredDevice(
                    deviceId = deviceId,
                    name = row[MonitoredDevices.name],
                    ownerFamilyUserId = row[MonitoredDevices.ownerFamilyUserId],
                    createdAt = row[MonitoredDevices.createdAt].toString(),
                    lastHeartbeatAt = heartbeats[deviceId]?.toString(),
                    revokedAt = revokedAt[deviceId]?.toString(),
                )
            }
    }

    /** requirements.md 34章: 端末登録(ペアリングコード発行前に、家族が端末の枠を作る)。 */
    suspend fun createDeviceWithOwner(name: String, ownerFamilyUserId: String): String = dbQuery {
        val deviceId = UUID.randomUUID().toString()
        val now = Instant.now()
        MonitoredDevices.insert {
            it[id] = deviceId
            it[MonitoredDevices.name] = name
            // 関数引数 ownerFamilyUserId がテーブル列 MonitoredDevices.ownerFamilyUserId を
            // シャドーイングするため、列側は明示的に修飾する。
            it[MonitoredDevices.ownerFamilyUserId] = ownerFamilyUserId
            it[createdAt] = now
        }
        DeviceMembers.insert {
            it[DeviceMembers.deviceId] = deviceId
            it[familyUserId] = ownerFamilyUserId
            it[minRiskLevel] = "INFO"
            it[canDisconnectCall] = true
            it[canEditWhitelist] = true
            it[createdAt] = now
        }
        deviceId
    }

    suspend fun issuePairingCode(deviceId: String): String = dbQuery {
        val code = generateRandomToken(6).take(8).uppercase()
        val now = Instant.now()
        PairingCodes.insert {
            it[PairingCodes.code] = code
            it[PairingCodes.deviceId] = deviceId
            it[createdAt] = now
            it[expiresAt] = now.plus(PAIRING_CODE_TTL_MINUTES, ChronoUnit.MINUTES)
        }
        code
    }

    /** requirements.md 34章: ペアリングコードを引き換え、APIキーと署名検証用公開鍵を払い出す。 */
    suspend fun exchangePairingCode(code: String): DevicePairingResult? = dbQuery {
        val row = PairingCodes
            .select { PairingCodes.code eq code }
            .singleOrNull() ?: return@dbQuery null

        val now = Instant.now()
        if (row[PairingCodes.usedAt] != null || row[PairingCodes.expiresAt].isBefore(now)) {
            return@dbQuery null
        }

        val deviceId = row[PairingCodes.deviceId]
        val apiKey = generateRandomToken(32)

        PairingCodes.update({ PairingCodes.code eq code }) {
            it[usedAt] = now
        }
        DevicePairings.insert {
            it[DevicePairings.deviceId] = deviceId
            it[apiKeyHash] = sha256Hex(apiKey)
            it[serverPublicKey] = CommandKeys.publicKeyBase64
            it[pairedAt] = now
        }

        DevicePairingResult(deviceId = deviceId, apiKey = apiKey, serverPublicKey = CommandKeys.publicKeyBase64)
    }

    suspend fun revoke(deviceId: String) = dbQuery {
        DevicePairings.update({ DevicePairings.deviceId eq deviceId }) {
            it[revokedAt] = Instant.now()
        }
    }

    /**
     * requirements.md 35.3, 35.4章: ペアリング済み(かつ無効化されていない)端末のうち、
     * 直近のハートビートが閾値より古い(または一度もハートビートが無くペアリングからの経過が
     * 閾値を超えた)ものを返す。HeartbeatWatchdogが定期的に呼び出す。
     */
    suspend fun findStaleDevices(thresholdMinutes: Long): List<String> = dbQuery {
        val threshold = Instant.now().minusSeconds(thresholdMinutes * 60)
        val lastHeartbeat = DeviceHeartbeats.receivedAt.max()
        val heartbeatByDevice = DeviceHeartbeats
            .slice(DeviceHeartbeats.deviceId, lastHeartbeat)
            .selectAll()
            .groupBy(DeviceHeartbeats.deviceId)
            .associate { it[DeviceHeartbeats.deviceId] to it[lastHeartbeat] }

        (MonitoredDevices innerJoin DevicePairings)
            .select { DevicePairings.revokedAt.isNull() }
            .mapNotNull { row ->
                val deviceId = row[MonitoredDevices.id]
                val lastSeen = heartbeatByDevice[deviceId] ?: row[MonitoredDevices.createdAt]
                if (lastSeen.isBefore(threshold)) deviceId else null
            }
    }
}

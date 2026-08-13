package com.fraudguard.server.db.repository

import com.fraudguard.server.db.dbQuery
import com.fraudguard.server.db.tables.DeviceHeartbeats
import java.time.Instant
import java.util.UUID
import org.jetbrains.exposed.sql.insert

/** requirements.md 35章[v2]: 監視継続性のハートビート記録。 */
object HeartbeatRepository {
    suspend fun record(
        deviceId: String,
        notificationListenerEnabled: Boolean,
        roleDialerHeld: Boolean,
        appVersion: String,
    ) = dbQuery {
        DeviceHeartbeats.insert {
            it[id] = UUID.randomUUID().toString()
            it[DeviceHeartbeats.deviceId] = deviceId
            it[receivedAt] = Instant.now()
            it[DeviceHeartbeats.notificationListenerEnabled] = notificationListenerEnabled
            it[DeviceHeartbeats.roleDialerHeld] = roleDialerHeld
            it[DeviceHeartbeats.appVersion] = appVersion
        }
        // TODO: 35.3章の死活監視(想定間隔を超えた途絶の検知)は別途スケジューラ(定期ジョブ)で実装する。
        //       ここでは受信の記録のみ行う。
    }
}

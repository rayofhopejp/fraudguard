package com.fraudguard.server.routes

import com.fraudguard.server.db.repository.DeviceHealthService
import com.fraudguard.server.db.repository.HeartbeatRepository
import com.fraudguard.server.domain.model.HeartbeatRequest
import com.fraudguard.server.security.DevicePrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * requirements.md 35章[v2]: 監視継続性の死活監視。
 * ハートビート途絶のタイムアウト検知(35.3章)は別途 db.repository.HeartbeatWatchdog(定期ジョブ)が行う。
 * ここでは受信の記録と、通知アクセス許可の即時失効検知を行う。
 */
fun Route.heartbeatRoutes() {
    post("/devices/{deviceId}/heartbeat") {
        val device = call.principal<DevicePrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (deviceId != device.deviceId) return@post call.respond(HttpStatusCode.Forbidden)

        val request = call.receive<HeartbeatRequest>()
        HeartbeatRepository.record(
            deviceId = device.deviceId,
            notificationListenerEnabled = request.notificationListenerEnabled,
            roleDialerHeld = request.roleDialerHeld,
            appVersion = request.appVersion,
        )

        // requirements.md 35.1, 35.3章: 通知アクセスは常時有効であることを前提としており、失効は
        // 詐欺犯からの指示等による無効化の兆候となりうる。ROLE_DIALERは任意機能のため対象外
        // (未取得が正常な端末も多く、それ自体は異常の兆候ではない)。
        if (!request.notificationListenerEnabled) {
            DeviceHealthService.reportPermissionRevoked(device.deviceId)
        }

        call.respond(HttpStatusCode.OK)
    }
}

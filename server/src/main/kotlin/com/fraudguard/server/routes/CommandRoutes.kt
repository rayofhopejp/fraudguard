package com.fraudguard.server.routes

import com.fraudguard.server.db.repository.CommandRepository
import com.fraudguard.server.db.repository.DeviceRepository
import com.fraudguard.server.domain.model.CommandExecutionReport
import com.fraudguard.server.domain.model.CreateCommandRequest
import com.fraudguard.server.push.FcmClientProvider
import com.fraudguard.server.security.DevicePrincipal
import com.fraudguard.server.security.FamilyUserPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * requirements.md 8章: 遠隔コマンド発行(Family Webがfamily-authで呼ぶ)。
 * 発行直後にFCM Data Messageでの即時配信を試み、成功時のみdelivered=trueにする。
 * 未登録トークン・送信失敗時は8.1章[v2]のpendingポーリングにフォールバックする(command自体は
 * 常にAPIレスポンスとして即時返すので、Family Web側の体験は送信可否に関わらず変わらない)。
 * TODO: 対象通話が現在ACTIVEであることのサーバー側事前確認(現状は端末側検証のみに依存)。
 */
fun Route.familyCommandRoutes() {
    post("/devices/{deviceId}/commands") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@post call.respond(HttpStatusCode.BadRequest)

        if (!DeviceRepository.canDisconnectCall(deviceId, principal.familyUserId)) {
            return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "not_permitted"))
        }

        val request = call.receive<CreateCommandRequest>()
        val command = CommandRepository.create(
            deviceId = deviceId,
            callId = request.callId,
            type = request.type,
            issuedByFamilyUserId = principal.familyUserId,
        )

        val fcmToken = DeviceRepository.findFcmToken(deviceId)
        if (fcmToken != null) {
            val delivered = FcmClientProvider.get().sendCommandDataMessage(fcmToken, command)
            if (delivered) {
                CommandRepository.markDelivered(command.commandId)
            }
        }

        call.respond(HttpStatusCode.Accepted, command)
    }
}

/**
 * requirements.md 8.1章[v2]: FCM未達時のフォールバックポーリング・実行結果報告
 * (Monitorアプリがdevice-authで呼ぶ)。
 */
fun Route.deviceCommandRoutes() {
    get("/devices/{deviceId}/commands/pending") {
        val device = call.principal<DevicePrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        if (deviceId != device.deviceId) return@get call.respond(HttpStatusCode.Forbidden)

        val pending = CommandRepository.listPending(device.deviceId)
        pending.forEach { CommandRepository.markDelivered(it.commandId) }
        call.respond(HttpStatusCode.OK, pending)
    }

    post("/devices/{deviceId}/commands/{commandId}/report") {
        val device = call.principal<DevicePrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val commandId = call.parameters["commandId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (deviceId != device.deviceId) return@post call.respond(HttpStatusCode.Forbidden)

        val report = call.receive<CommandExecutionReport>()
        val executedAt = try {
            Instant.parse(report.executedAt)
        } catch (e: DateTimeParseException) {
            Instant.now()
        }

        // requirements.md 8.2章: 誰が切断を要求したか等はcommand作成時に記録済み。ここでは実行結果を記録する。
        val updated = CommandRepository.reportExecution(
            commandId = commandId,
            deviceId = device.deviceId,
            success = report.success,
            failureReason = report.failureReason,
            executedAt = executedAt,
        )
        if (!updated) return@post call.respond(HttpStatusCode.NotFound)
        call.respond(HttpStatusCode.OK, mapOf("commandId" to commandId))
    }
}

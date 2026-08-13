package com.fraudguard.server.routes

import com.fraudguard.server.db.repository.DeviceRepository
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
import kotlinx.serialization.Serializable

@Serializable
data class CreatePairingCodeRequest(val deviceName: String)

@Serializable
data class PairingCodeResponse(val deviceId: String, val pairingCode: String)

@Serializable
data class PairingExchangeRequest(val pairingCode: String)

@Serializable
data class RegisterFcmTokenRequest(val fcmToken: String)

/** requirements.md 23章, 34章: 端末一覧・ペアリングコード発行(Family Webがfamily-authで呼ぶ)。 */
fun Route.deviceRoutes() {
    get("/devices") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        call.respond(HttpStatusCode.OK, DeviceRepository.listForFamilyUser(principal.familyUserId))
    }

    // requirements.md 34章: 新しい監視対象端末の枠を作り、ペアリングコードを発行する。
    post("/devices/pairing-codes") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val request = call.receive<CreatePairingCodeRequest>()

        val deviceId = DeviceRepository.createDeviceWithOwner(request.deviceName, principal.familyUserId)
        val code = DeviceRepository.issuePairingCode(deviceId)
        call.respond(HttpStatusCode.Created, PairingCodeResponse(deviceId = deviceId, pairingCode = code))
    }

    post("/devices/{deviceId}/revoke") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (!DeviceRepository.isMember(deviceId, principal.familyUserId)) return@post call.respond(HttpStatusCode.Forbidden)

        // requirements.md 25章: デバイス紛失時、ペアリング無効化のみで即座に遮断できる設計。
        DeviceRepository.revoke(deviceId)
        call.respond(HttpStatusCode.OK)
    }
}

/**
 * requirements.md 34章: Monitorアプリがペアリングコードを引き換えて登録する。
 * この時点では端末はまだAPIキーを持たない(コード自体が一時的な認証情報)ため、
 * device-auth / family-auth のどちらにも属さない未認証エンドポイントとして扱う。
 */
fun Route.pairingExchangeRoute() {
    post("/devices/pairing") {
        val request = call.receive<PairingExchangeRequest>()
        val result = DeviceRepository.exchangePairingCode(request.pairingCode)
            ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_or_expired_code"))
        call.respond(HttpStatusCode.Created, result)
    }
}

/** requirements.md 8章[v2]: Monitorアプリが自身のFCMトークンを登録・更新する(device-auth)。 */
fun Route.deviceFcmTokenRoute() {
    post("/devices/{deviceId}/fcm-token") {
        val device = call.principal<DevicePrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (deviceId != device.deviceId) return@post call.respond(HttpStatusCode.Forbidden)

        val request = call.receive<RegisterFcmTokenRequest>()
        DeviceRepository.updateFcmToken(device.deviceId, request.fcmToken)
        call.respond(HttpStatusCode.OK)
    }
}

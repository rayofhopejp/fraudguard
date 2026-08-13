package com.fraudguard.server.routes

import com.fraudguard.server.db.repository.BlacklistRepository
import com.fraudguard.server.db.repository.DeviceRepository
import com.fraudguard.server.db.repository.WhitelistRepository
import com.fraudguard.server.domain.risk.PhoneNumberClassifier
import com.fraudguard.server.security.FamilyUserPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

@Serializable
data class WhitelistCreateRequest(val phoneNumber: String, val displayName: String, val note: String? = null)

@Serializable
data class BlacklistCreateRequest(val phoneNumber: String, val reason: String? = null)

/** requirements.md 23章, 6章: ホワイトリストCRUD。サーバー側を正とし、Monitorはローカルキャッシュへ同期する。 */
fun Route.whitelistRoutes() {
    get("/devices/{deviceId}/whitelist") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        if (!DeviceRepository.isMember(deviceId, principal.familyUserId)) return@get call.respond(HttpStatusCode.Forbidden)

        call.respond(HttpStatusCode.OK, WhitelistRepository.list(deviceId))
    }

    post("/devices/{deviceId}/whitelist") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (!DeviceRepository.isMember(deviceId, principal.familyUserId)) return@post call.respond(HttpStatusCode.Forbidden)

        val request = call.receive<WhitelistCreateRequest>()
        // requirements.md 5章: E.164へ正規化して保存する。RiskEngine/RiskEvaluationServiceの照合も
        // E.164ベースのため、ここで揃えておかないとホワイトリストが機能しない。
        val classification = PhoneNumberClassifier.classify(request.phoneNumber)
        if (classification !is PhoneNumberClassifier.Classification.Valid) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_phone_number"))
        }
        val entry = WhitelistRepository.add(
            deviceId = deviceId,
            phoneNumber = classification.e164,
            displayName = request.displayName,
            note = request.note,
            createdBy = principal.familyUserId,
        )
        call.respond(HttpStatusCode.Created, entry)
    }

    delete("/devices/{deviceId}/whitelist/{entryId}") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        val entryId = call.parameters["entryId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        if (!DeviceRepository.isMember(deviceId, principal.familyUserId)) return@delete call.respond(HttpStatusCode.Forbidden)

        val deleted = WhitelistRepository.delete(deviceId, entryId)
        call.respond(if (deleted) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
    }

    // requirements.md 18章[v2]: ブラックリストは「常にCRITICAL即時警告」の効果のみ持つ。自動拒否はしない。
    get("/devices/{deviceId}/blacklist") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        if (!DeviceRepository.isMember(deviceId, principal.familyUserId)) return@get call.respond(HttpStatusCode.Forbidden)

        call.respond(HttpStatusCode.OK, BlacklistRepository.list(deviceId))
    }

    post("/devices/{deviceId}/blacklist") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (!DeviceRepository.isMember(deviceId, principal.familyUserId)) return@post call.respond(HttpStatusCode.Forbidden)

        val request = call.receive<BlacklistCreateRequest>()
        val classification = PhoneNumberClassifier.classify(request.phoneNumber)
        if (classification !is PhoneNumberClassifier.Classification.Valid) {
            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_phone_number"))
        }
        val entry = BlacklistRepository.add(deviceId, classification.e164, request.reason, principal.familyUserId)
        call.respond(HttpStatusCode.Created, entry)
    }
}

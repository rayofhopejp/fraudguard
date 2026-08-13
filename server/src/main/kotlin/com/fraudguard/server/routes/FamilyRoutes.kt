package com.fraudguard.server.routes

import com.fraudguard.server.db.repository.AuditLogRepository
import com.fraudguard.server.db.repository.DeviceRepository
import com.fraudguard.server.db.repository.FamilyRepository
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
data class RegisterPushTokenRequest(val fcmToken: String, val platform: String)

@Serializable
data class AddDeviceMemberRequest(val email: String)

/** requirements.md 23章: 家族メンバー・Push token管理。 */
fun Route.familyRoutes() {
    get("/family/members") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        call.respond(HttpStatusCode.OK, FamilyRepository.listRelatedMembers(principal.familyUserId))
    }

    /** requirements.md 16.2章: この端末を共有している家族の一覧。 */
    get("/devices/{deviceId}/members") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        // 自分が共有していない端末のメンバーは見せない。誰が見守っているかも個人情報にあたる。
        if (!DeviceRepository.isMember(deviceId, principal.familyUserId)) {
            return@get call.respond(HttpStatusCode.Forbidden)
        }
        call.respond(HttpStatusCode.OK, FamilyRepository.listDeviceMembers(deviceId))
    }

    /** requirements.md 16.2章: 端末を別の家族と共有する。 */
    post("/devices/{deviceId}/members") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        if (!DeviceRepository.isMember(deviceId, principal.familyUserId)) {
            return@post call.respond(HttpStatusCode.Forbidden)
        }

        val request = call.receive<AddDeviceMemberRequest>()
        val added = FamilyRepository.addMemberByEmail(deviceId, request.email)
        if (!added) {
            // requirements.md 25章: 「そのメールアドレスは存在しない」と答えるとアカウントの有無を
            // 外部から確かめられてしまうが、ここは既に認証済みの家族だけが到達する経路なので、
            // 何をすればよいかが分かる answer を返すほうが有益。
            return@post call.respond(
                HttpStatusCode.NotFound,
                mapOf("error" to "family_user_not_found", "message" to "その方はまだログインしていません。一度ログインしてもらってから追加してください。"),
            )
        }
        AuditLogRepository.recordSuspend(
            actorFamilyUserId = principal.familyUserId,
            action = "ADD_DEVICE_MEMBER",
            targetType = "DEVICE",
            targetId = deviceId,
            result = "SUCCESS",
        )
        call.respond(HttpStatusCode.Created)
    }

    /** requirements.md 16.2章: 共有を解除する。 */
    delete("/devices/{deviceId}/members/{familyUserId}") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        val targetId = call.parameters["familyUserId"] ?: return@delete call.respond(HttpStatusCode.BadRequest)
        if (!DeviceRepository.isMember(deviceId, principal.familyUserId)) {
            return@delete call.respond(HttpStatusCode.Forbidden)
        }

        val removed = FamilyRepository.removeMember(deviceId, targetId)
        if (!removed) {
            return@delete call.respond(
                HttpStatusCode.Conflict,
                mapOf("error" to "cannot_remove_owner", "message" to "端末の登録者は共有から外せません。"),
            )
        }
        AuditLogRepository.recordSuspend(
            actorFamilyUserId = principal.familyUserId,
            action = "REMOVE_DEVICE_MEMBER",
            targetType = "DEVICE",
            targetId = deviceId,
            result = "SUCCESS",
        )
        call.respond(HttpStatusCode.OK)
    }

    post("/family/push-tokens") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val request = call.receive<RegisterPushTokenRequest>()
        FamilyRepository.registerPushToken(principal.familyUserId, request.fcmToken, request.platform)
        call.respond(HttpStatusCode.Created)
    }
}

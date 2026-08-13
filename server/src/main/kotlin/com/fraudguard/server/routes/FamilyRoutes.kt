package com.fraudguard.server.routes

import com.fraudguard.server.db.repository.FamilyRepository
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
data class RegisterPushTokenRequest(val fcmToken: String, val platform: String)

/** requirements.md 23章: 家族メンバー・Push token管理。 */
fun Route.familyRoutes() {
    get("/family/members") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        call.respond(HttpStatusCode.OK, FamilyRepository.listRelatedMembers(principal.familyUserId))
    }

    post("/family/push-tokens") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val request = call.receive<RegisterPushTokenRequest>()
        FamilyRepository.registerPushToken(principal.familyUserId, request.fcmToken, request.platform)
        call.respond(HttpStatusCode.Created)
    }
}

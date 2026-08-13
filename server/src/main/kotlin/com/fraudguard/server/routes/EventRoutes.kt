package com.fraudguard.server.routes

import com.fraudguard.server.db.repository.DeviceRepository
import com.fraudguard.server.db.repository.EventRepository
import com.fraudguard.server.db.repository.RiskEvaluationService
import com.fraudguard.server.domain.model.CreateEventRequest
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

/**
 * requirements.md 23章: POST /events (Monitorアプリがdevice-authで送信)
 * eventIdの冪等性(requirements.md 24章)はEventRepository側のON CONFLICT DO NOTHINGで担保する。
 * RiskEvaluationServiceが単一イベント判定(7章)と相関判定(14章)を行う。
 * TODO: FCM Push配信(16章)をRiskEvaluationServiceの結果に応じてここから呼び出す。
 */
fun Route.deviceEventRoutes() {
    post("/events") {
        val device = call.principal<DevicePrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val request = call.receive<CreateEventRequest>()
        if (request.deviceId != device.deviceId) {
            return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "deviceId mismatch"))
        }
        RiskEvaluationService.ingestEvent(request, device.deviceId)
        call.respond(HttpStatusCode.Accepted, mapOf("eventId" to request.eventId))
    }
}

/**
 * requirements.md 23章: GET /devices/{id}/events, POST /events/{id}/acknowledge
 * (Family Webがfamily-authで呼ぶ)
 */
fun Route.familyEventRoutes() {
    get("/devices/{deviceId}/events") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val deviceId = call.parameters["deviceId"] ?: return@get call.respond(HttpStatusCode.BadRequest)

        if (!DeviceRepository.isMember(deviceId, principal.familyUserId)) {
            return@get call.respond(HttpStatusCode.Forbidden)
        }
        call.respond(HttpStatusCode.OK, EventRepository.listForDevice(deviceId))
    }

    post("/events/{eventId}/acknowledge") {
        val principal = call.principal<FamilyUserPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val eventId = call.parameters["eventId"] ?: return@post call.respond(HttpStatusCode.BadRequest)

        val event = EventRepository.find(eventId) ?: return@post call.respond(HttpStatusCode.NotFound)
        if (!DeviceRepository.isMember(event.deviceId, principal.familyUserId)) {
            return@post call.respond(HttpStatusCode.Forbidden)
        }

        // requirements.md 19章: 誰が確認したかを他の家族にも共有できるよう記録する。
        val acknowledged = EventRepository.markAcknowledged(eventId, principal.familyUserId)
        call.respond(HttpStatusCode.OK, mapOf("eventId" to eventId, "acknowledged" to acknowledged))
    }
}

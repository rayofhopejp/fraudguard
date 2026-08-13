package com.fraudguard.server.plugins

import com.fraudguard.server.routes.deviceCommandRoutes
import com.fraudguard.server.routes.deviceEventRoutes
import com.fraudguard.server.routes.deviceFcmTokenRoute
import com.fraudguard.server.routes.deviceRoutes
import com.fraudguard.server.routes.familyCommandRoutes
import com.fraudguard.server.routes.familyEventRoutes
import com.fraudguard.server.routes.familyRoutes
import com.fraudguard.server.routes.heartbeatRoutes
import com.fraudguard.server.routes.pairingExchangeRoute
import com.fraudguard.server.routes.whitelistRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureRouting() {
    routing {
        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        // ペアリングコード交換は一時コード自体が認証情報のため未認証エンドポイント。
        pairingExchangeRoute()

        // Monitorアプリからのアクセス(端末APIキー認証)。
        authenticate("device-auth") {
            deviceEventRoutes()
            heartbeatRoutes()
            deviceCommandRoutes()
            deviceFcmTokenRoute()
        }

        // Family Webからのアクセス(Cognito JWT認証)。
        authenticate("family-auth") {
            familyEventRoutes()
            familyCommandRoutes()
            whitelistRoutes()
            deviceRoutes()
            familyRoutes()
        }
    }
}

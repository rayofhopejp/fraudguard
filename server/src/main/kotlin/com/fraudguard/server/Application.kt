package com.fraudguard.server

import com.fraudguard.server.db.DatabaseFactory
import com.fraudguard.server.db.repository.HeartbeatWatchdog
import com.fraudguard.server.plugins.configureMonitoring
import com.fraudguard.server.plugins.configureRouting
import com.fraudguard.server.plugins.configureSecurity
import com.fraudguard.server.plugins.configureSerialization
import com.fraudguard.server.notify.FamilyNotifierProvider
import com.fraudguard.server.push.FcmClientProvider
import com.fraudguard.server.security.CommandKeys
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.launch

fun main(args: Array<String>) {
    embeddedServer(Netty, port = 8080, module = Application::module).start(wait = true)
}

fun Application.module() {
    val dbConfig = environment.config.config("database")
    try {
        DatabaseFactory.init(
            jdbcUrl = dbConfig.property("jdbcUrl").getString(),
            user = dbConfig.property("user").getString(),
            password = dbConfig.property("password").getString(),
        )
    } catch (e: Exception) {
        // ローカルでPostgreSQLが未起動でもルーティング等の確認ができるよう、起動は継続する。
        log.warn("Database initialization failed (expected if Postgres is not running yet): ${e.message}")
    }

    try {
        val privateKeyPath = environment.config.property("commandSigning.privateKeyPath").getString()
        CommandKeys.init(privateKeyPath)
    } catch (e: Exception) {
        // deployment.md Part A5の鍵を未生成のローカル開発環境でも起動は継続する。
        // 遠隔コマンドの発行・検証(8章)はこの鍵が無いと機能しない。
        log.warn("Command signing key not loaded (remote-disconnect commands will fail): ${e.message}")
    }

    val slackWebhookUrl = environment.config.propertyOrNull("slack.webhookUrl")?.getString().orEmpty()
    FamilyNotifierProvider.init(slackWebhookUrl)
    if (slackWebhookUrl.isBlank()) {
        log.warn("SLACK_WEBHOOK_URL not set; family notifications will not be sent (requirements.md 2.2章)")
    }

    val fcmServiceAccountPath = environment.config.propertyOrNull("fcm.serviceAccountPath")?.getString().orEmpty()
    FcmClientProvider.init(fcmServiceAccountPath)

    // requirements.md 35.3章: アプリケーションのライフサイクルに紐づくコルーチンとして死活監視ジョブを起動する。
    launch { HeartbeatWatchdog.run() }

    configureSerialization()
    configureSecurity()
    configureMonitoring()
    configureRouting()
}

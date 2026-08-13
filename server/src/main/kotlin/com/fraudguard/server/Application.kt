package com.fraudguard.server

import com.fraudguard.server.db.DatabaseFactory
import com.fraudguard.server.db.repository.HeartbeatWatchdog
import com.fraudguard.server.plugins.configureCors
import com.fraudguard.server.plugins.configureMonitoring
import com.fraudguard.server.plugins.configureRouting
import com.fraudguard.server.plugins.configureSecurity
import com.fraudguard.server.plugins.configureSerialization
import com.fraudguard.server.notify.FamilyNotifierProvider
import com.fraudguard.server.push.FcmClientProvider
import com.fraudguard.server.security.CommandKeys
import io.ktor.server.application.Application
import io.ktor.server.application.log
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.launch

/**
 * 単純な `embeddedServer(Netty, port = ..., module = ...)` は src/main/resources/application.conf を
 * 読み込まない(Ktorの規約的なEngineMain経由のブートストラップでのみHOCON設定が読まれるため)。
 * それに気づかずこの形で書いていたため、DATABASE_URL等の環境変数が実際には一切反映されない
 * 状態でリリースされかけていた(ローカル実機テストで発覚)。ApplicationConfigを明示的に渡す。
 */
fun main(args: Array<String>) {
    val environment = applicationEngineEnvironment {
        config = ApplicationConfig("application.conf")
        connector {
            port = System.getenv("PORT")?.toIntOrNull() ?: 8080
        }
        module(Application::module)
    }
    embeddedServer(Netty, environment).start(wait = true)
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
    // requirements.md 10.3章: 通知に載せる「家族の通話としてマーク」リンクの生成に使う。
    val publicBaseUrl = environment.config.propertyOrNull("publicBaseUrl")?.getString().orEmpty()
    FamilyNotifierProvider.init(slackWebhookUrl, publicBaseUrl)
    if (publicBaseUrl.isBlank()) {
        log.warn("PUBLIC_BASE_URL not set; Slack notifications will not carry the 'mark as family call' link")
    }
    if (slackWebhookUrl.isBlank()) {
        log.warn("SLACK_WEBHOOK_URL not set; family notifications will not be sent (requirements.md 2.2章)")
    }

    val fcmServiceAccountPath = environment.config.propertyOrNull("fcm.serviceAccountPath")?.getString().orEmpty()
    FcmClientProvider.init(fcmServiceAccountPath)

    // requirements.md 35.3章: アプリケーションのライフサイクルに紐づくコルーチンとして死活監視ジョブを起動する。
    launch { HeartbeatWatchdog.run() }

    configureSerialization()
    configureCors()
    configureSecurity()
    configureMonitoring()
    configureRouting()
}

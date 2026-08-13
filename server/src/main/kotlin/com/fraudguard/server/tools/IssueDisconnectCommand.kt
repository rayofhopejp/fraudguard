package com.fraudguard.server.tools

import com.fraudguard.server.db.DatabaseFactory
import com.fraudguard.server.db.repository.CommandRepository
import com.fraudguard.server.db.repository.DeviceRepository
import com.fraudguard.server.db.repository.EventRepository
import com.fraudguard.server.domain.model.CommandType
import com.fraudguard.server.domain.model.EventType
import com.fraudguard.server.security.CommandKeys
import kotlinx.coroutines.runBlocking

/**
 * ローカル実機テスト用の開発ツール。Family Web(family-auth)を経由せず、
 * 直近の通話イベントからcallIdを拾って遠隔切断コマンドを発行する。
 * 本番では POST /devices/{deviceId}/commands(family-auth)が同じことを行う。
 *
 * 実行例:
 *   DATABASE_URL=... COMMAND_SIGNING_PRIVATE_KEY_PATH=... \
 *   ./gradlew issueDisconnectCommand --args="<deviceId>"
 */
fun main(args: Array<String>) {
    val deviceId = args.getOrNull(0) ?: error("usage: issueDisconnectCommand <deviceId>")

    DatabaseFactory.init(
        jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/fraudguard",
        user = System.getenv("DATABASE_USER") ?: "fraudguard",
        password = System.getenv("DATABASE_PASSWORD") ?: "fraudguard",
    )
    CommandKeys.init(
        System.getenv("COMMAND_SIGNING_PRIVATE_KEY_PATH")
            ?: error("COMMAND_SIGNING_PRIVATE_KEY_PATH is required"),
    )

    runBlocking {
        val callEvent = EventRepository.listForDevice(deviceId)
            .firstOrNull { it.type in setOf(EventType.CALL_INCOMING, EventType.CALL_OUTGOING) && it.metadata.callId != null }
            ?: error("この端末の通話イベント(callId付き)が見つかりません。先に通話を発生させてください。")

        val callId = callEvent.metadata.callId!!
        // 開発ツールのため、端末の所有者を発行者として扱う(本番はfamily-authのプリンシパル)。
        val issuedBy = DeviceRepository.ownerOf(deviceId) ?: error("端末の所有者が見つかりません")
        val command = CommandRepository.create(
            deviceId = deviceId,
            callId = callId,
            type = CommandType.DISCONNECT_CALL,
            issuedByFamilyUserId = issuedBy,
        )

        println("==============================================")
        println("対象通話: callId=$callId (${callEvent.metadata.phoneNumber ?: "番号不明"} / ${callEvent.timestamp})")
        println("commandId: ${command.commandId}")
        println("expiresAt: ${command.expiresAt}")
        println("端末が次回ポーリング(最大5秒)で取得し、検証OKなら切断します。")
        println("==============================================")
    }
}

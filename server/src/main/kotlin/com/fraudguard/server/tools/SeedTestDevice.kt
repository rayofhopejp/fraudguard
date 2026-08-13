package com.fraudguard.server.tools

import com.fraudguard.server.db.DatabaseFactory
import com.fraudguard.server.db.repository.DeviceRepository
import com.fraudguard.server.db.repository.FamilyUserRepository
import com.fraudguard.server.security.CommandKeys
import kotlinx.coroutines.runBlocking

/**
 * ローカル実機テスト用の開発ツール。Cognito(family-auth)を経由せず、DBへ直接
 * 家族ユーザー・監視端末・ペアリングコードを作成する。本番運用では使わない。
 *
 * 実行例:
 *   DATABASE_URL=jdbc:postgresql://localhost:5432/fraudguard \
 *   COMMAND_SIGNING_PRIVATE_KEY_PATH=/path/to/command-signing-private.pem \
 *   ./gradlew seedTestDevice --args="テスト端末"
 *
 * 同じ開発者が繰り返し実行しても家族ユーザーが重複作成されないよう、固定のcognitoSubを使う
 * (FamilyUserRepository.resolveOrCreateはsubで既存レコードを検索するため)。
 */
fun main(args: Array<String>) {
    val deviceName = args.getOrNull(0) ?: "テスト端末"

    DatabaseFactory.init(
        jdbcUrl = System.getenv("DATABASE_URL") ?: "jdbc:postgresql://localhost:5432/fraudguard",
        user = System.getenv("DATABASE_USER") ?: "fraudguard",
        password = System.getenv("DATABASE_PASSWORD") ?: "fraudguard",
    )
    val privateKeyPath = System.getenv("COMMAND_SIGNING_PRIVATE_KEY_PATH")
        ?: error("COMMAND_SIGNING_PRIVATE_KEY_PATH is required")
    CommandKeys.init(privateKeyPath)

    runBlocking {
        val family = FamilyUserRepository.resolveOrCreate(
            cognitoSub = "local-dev-seed-fixed-sub",
            email = "local-dev-seed@example.invalid",
        )
        val deviceId = DeviceRepository.createDeviceWithOwner(deviceName, family.familyUserId)
        val pairingCode = DeviceRepository.issuePairingCode(deviceId)

        println("==============================================")
        println("familyUserId: ${family.familyUserId}")
        println("deviceId:     $deviceId")
        println("pairingCode:  $pairingCode   (発行から15分以内にMonitorアプリで入力すること)")
        println("==============================================")
    }
}

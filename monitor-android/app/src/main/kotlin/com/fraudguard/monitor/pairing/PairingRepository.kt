package com.fraudguard.monitor.pairing

/**
 * requirements.md 34章: ペアリングコード引き換え・端末登録・同意記録。
 *
 * TODO: 26章[v2]の同意フロー(同意内容の表示・同意日時のサーバーaudit_logsへの記録)は
 *       ui/onboarding/PairingScreen側の表示ロジックとして別途実装する。
 */
interface PairingRepository {
    suspend fun pair(pairingCode: String): PairingOutcome
    fun isPaired(): Boolean
    fun getDeviceId(): String?

    /** requirements.md 25章: device-auth用のAPIキー。平文ログ出力禁止。 */
    fun getApiKey(): String?

    /** requirements.md 8.1章[v2]: 遠隔コマンド署名検証用のサーバー公開鍵(Base64)。 */
    fun getServerPublicKey(): String?
}

sealed class PairingOutcome {
    data class Success(val deviceId: String) : PairingOutcome()
    data class Failure(val reason: String) : PairingOutcome()
}

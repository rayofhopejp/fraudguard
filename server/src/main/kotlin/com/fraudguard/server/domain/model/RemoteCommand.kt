package com.fraudguard.server.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class CommandType {
    DISCONNECT_CALL,
}

/**
 * requirements.md 8.1章: 遠隔コマンド。サーバーがEd25519秘密鍵で署名し、
 * Monitorアプリはペアリング時に受け取った公開鍵で検証する(対称鍵HMACは採用しない)。
 */
@Serializable
data class RemoteCommand(
    val commandId: String,
    val deviceId: String,
    val callId: String,
    val type: CommandType,
    val issuedAt: String,
    val expiresAt: String,
    val nonce: String,
    val signature: String, // Base64(Ed25519 signature over the canonical payload)
    val issuedByFamilyUserId: String,
)

@Serializable
data class CreateCommandRequest(
    val callId: String,
    val type: CommandType,
)

@Serializable
data class CommandExecutionReport(
    val commandId: String,
    val deviceId: String,
    val success: Boolean,
    val failureReason: String? = null,
    val executedAt: String,
)

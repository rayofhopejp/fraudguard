package com.fraudguard.server.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MonitoredDevice(
    val deviceId: String,
    val name: String,
    val ownerFamilyUserId: String,
    val createdAt: String,
    val lastHeartbeatAt: String? = null,
)

/** requirements.md 34章: ペアリング完了時にサーバーが払い出す公開鍵情報。 */
@Serializable
data class DevicePairingResult(
    val deviceId: String,
    val apiKey: String,
    val serverPublicKey: String, // Ed25519公開鍵(Base64)。端末はこれでコマンド署名を検証する。
)

/** requirements.md 35章: ハートビートのリクエストボディ。 */
@Serializable
data class HeartbeatRequest(
    val deviceId: String,
    val timestamp: String,
    val notificationListenerEnabled: Boolean,
    val roleDialerHeld: Boolean,
    val appVersion: String,
)

@Serializable
data class WhitelistEntry(
    val entryId: String,
    val deviceId: String,
    val phoneNumber: String,
    val displayName: String,
    val enabled: Boolean,
    val note: String? = null,
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String,
)

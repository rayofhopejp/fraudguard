package com.fraudguard.server.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * requirements.md 20.2章のエンティティに対応するExposedテーブル定義。
 * 個人・家族規模を前提としているため、正規化は最低限に留めている。
 */

object FamilyUsers : Table("family_users") {
    val id = varchar("id", 36)
    val cognitoSub = varchar("cognito_sub", 128).uniqueIndex()
    val displayName = varchar("display_name", 100)
    val email = varchar("email", 255).uniqueIndex()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object MonitoredDevices : Table("monitored_devices") {
    val id = varchar("id", 36)
    val name = varchar("name", 100)
    val ownerFamilyUserId = varchar("owner_family_user_id", 36).references(FamilyUsers.id)
    val createdAt = timestamp("created_at")
    // requirements.md 8章[v2]: 遠隔コマンドのFCM即時送信先。Monitorアプリがトークン発行/更新のたびに登録する。
    val fcmToken = varchar("fcm_token", 512).nullable()
    override val primaryKey = PrimaryKey(id)
}

/** 監視対象端末と家族ユーザーの多対多関連(requirements.md 21章)。 */
object DeviceMembers : Table("device_members") {
    val deviceId = varchar("device_id", 36).references(MonitoredDevices.id)
    val familyUserId = varchar("family_user_id", 36).references(FamilyUsers.id)
    val minRiskLevel = varchar("min_risk_level", 20) // 16.3: 受け取る最低リスクレベル
    val canDisconnectCall = bool("can_disconnect_call")
    val canEditWhitelist = bool("can_edit_whitelist")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(deviceId, familyUserId)
}

/** 家族ユーザーの複数Push端末(requirements.md 16.2章)。 */
object PushDevices : Table("push_devices") {
    val id = varchar("id", 36)
    val familyUserId = varchar("family_user_id", 36).references(FamilyUsers.id)
    val fcmToken = varchar("fcm_token", 512)
    val platform = varchar("platform", 20) // "web" | "android"
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Events : Table("events") {
    val id = varchar("id", 36) // eventId
    val deviceId = varchar("device_id", 36).references(MonitoredDevices.id)
    val type = varchar("type", 40)
    val riskLevel = varchar("risk_level", 20)
    val title = varchar("title", 200)
    val detail = varchar("detail", 2000)
    val timestamp = timestamp("event_timestamp")
    val metadataJson = text("metadata_json")
    val acknowledged = bool("acknowledged").default(false)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Whitelist : Table("whitelist") {
    val id = varchar("id", 36)
    val deviceId = varchar("device_id", 36).references(MonitoredDevices.id)
    val phoneNumber = varchar("phone_number", 32) // E.164正規化済み
    val displayName = varchar("display_name", 100)
    val enabled = bool("enabled").default(true)
    val note = varchar("note", 500).nullable()
    val createdBy = varchar("created_by", 36).references(FamilyUsers.id)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** requirements.md 18章[v2]: ブラックリスト登録は着信を常にCRITICAL即時警告する効果のみ持つ(自動拒否はしない)。 */
object Blacklist : Table("blacklist") {
    val id = varchar("id", 36)
    val deviceId = varchar("device_id", 36).references(MonitoredDevices.id)
    val phoneNumber = varchar("phone_number", 32)
    val reason = varchar("reason", 500).nullable()
    val createdBy = varchar("created_by", 36).references(FamilyUsers.id)
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** requirements.md 8章: 遠隔コマンド。 */
object RemoteCommands : Table("remote_commands") {
    val id = varchar("id", 36) // commandId
    val deviceId = varchar("device_id", 36).references(MonitoredDevices.id)
    val callId = varchar("call_id", 64)
    val type = varchar("type", 40)
    val issuedByFamilyUserId = varchar("issued_by_family_user_id", 36).references(FamilyUsers.id)
    val issuedAt = timestamp("issued_at")
    val expiresAt = timestamp("expires_at")
    val nonce = varchar("nonce", 64)
    val signature = varchar("signature", 512)
    val delivered = bool("delivered").default(false)
    val executedSuccess = bool("executed_success").nullable()
    val executedFailureReason = varchar("executed_failure_reason", 500).nullable()
    val executedAt = timestamp("executed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** requirements.md 19章: 誰が確認したか。 */
object Acknowledgements : Table("acknowledgements") {
    val id = varchar("id", 36)
    val eventId = varchar("event_id", 36).references(Events.id)
    val familyUserId = varchar("family_user_id", 36).references(FamilyUsers.id)
    val acknowledgedAt = timestamp("acknowledged_at")
    override val primaryKey = PrimaryKey(id)
}

/** requirements.md 8.2, 25章: 監査ログ。誰が・いつ・何を。個人情報(電話番号本体等)は極力載せない。 */
object AuditLogs : Table("audit_logs") {
    val id = varchar("id", 36)
    val actorFamilyUserId = varchar("actor_family_user_id", 36).nullable()
    val actorDeviceId = varchar("actor_device_id", 36).nullable()
    val action = varchar("action", 100)
    val targetType = varchar("target_type", 50).nullable()
    val targetId = varchar("target_id", 64).nullable()
    val result = varchar("result", 20) // "SUCCESS" | "FAILURE"
    val detail = varchar("detail", 1000).nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** requirements.md 35章[v2]: 監視継続性のハートビート。 */
object DeviceHeartbeats : Table("device_heartbeats") {
    val id = varchar("id", 36)
    val deviceId = varchar("device_id", 36).references(MonitoredDevices.id)
    val receivedAt = timestamp("received_at")
    val notificationListenerEnabled = bool("notification_listener_enabled")
    val roleDialerHeld = bool("role_dialer_held")
    val appVersion = varchar("app_version", 30)
    override val primaryKey = PrimaryKey(id)
}

/** requirements.md 34章[v2]: ペアリングと端末の署名検証用鍵。 */
object DevicePairings : Table("device_pairings") {
    val deviceId = varchar("device_id", 36).references(MonitoredDevices.id)
    val apiKeyHash = varchar("api_key_hash", 128) // APIキーは平文保存しない
    val serverPublicKey = varchar("server_public_key", 128) // Ed25519公開鍵(Base64)
    val pairedAt = timestamp("paired_at")
    val revokedAt = timestamp("revoked_at").nullable()
    override val primaryKey = PrimaryKey(deviceId)
}

/**
 * requirements.md 34章: 家族側が発行する短期間有効なワンタイムペアリングコード。
 * 監視端末はまだAPIキーを持たない段階でこのコードのみを認証情報として使う。
 */
object PairingCodes : Table("pairing_codes") {
    val code = varchar("code", 64)
    val deviceId = varchar("device_id", 36).references(MonitoredDevices.id)
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val usedAt = timestamp("used_at").nullable()
    override val primaryKey = PrimaryKey(code)
}

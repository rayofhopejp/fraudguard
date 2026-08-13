package com.fraudguard.monitor.risk

import kotlinx.serialization.Serializable

/**
 * requirements.md 15章, 22章: サーバー側(server/domain/model)と対になる型。
 * 現状はモノレポ内でも手動同期(KMP共通モジュール化は将来検討, requirements.md 33章の
 * モジュール分離方針と整合させる)。
 */
@Serializable
enum class RiskLevel { INFO, NOTICE, WARNING, CRITICAL }

@Serializable
enum class EventType {
    CALL_INCOMING,
    CALL_OUTGOING,
    CALL_LONG_DURATION,
    CALL_BURST_FOREIGN,
    SMS_RECEIVED,
    // requirements.md 11章: メッセージング系・遠隔操作系のいずれにも該当しない一般アプリ。
    APP_INSTALLED,
    APP_MESSAGING_INSTALLED,
    APP_REMOTE_CONTROL_INSTALLED,
    APP_LAUNCHED_AFTER_INSTALL,
    NOTIFICATION_OBSERVED,
    CORRELATED_RISK,
    DEVICE_HEALTH,
}

@Serializable
data class EventMetadata(
    val phoneNumber: String? = null,
    val callId: String? = null,
    val direction: String? = null,
    val durationSeconds: Long? = null,
    val packageName: String? = null,
    val appName: String? = null,
    val messageBody: String? = null,
    val sourceApp: String? = null,
    val reason: String? = null,
    val confidence: Double? = null,
)

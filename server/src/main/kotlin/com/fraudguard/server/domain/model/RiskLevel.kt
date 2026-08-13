package com.fraudguard.server.domain.model

import kotlinx.serialization.Serializable

/** requirements.md 15章: 最低限4段階のリスクレベル。 */
@Serializable
enum class RiskLevel {
    INFO,
    NOTICE,
    WARNING,
    CRITICAL,
}

/** requirements.md 22章 + v2: イベント種別。 */
@Serializable
enum class EventType {
    CALL_INCOMING,
    CALL_OUTGOING,
    CALL_LONG_DURATION,
    CALL_BURST_FOREIGN,
    SMS_RECEIVED,
    // requirements.md 11章「新規アプリは原則としてすべて家族へ通知」に対応する、
    // メッセージング系・遠隔操作系のいずれにも該当しない一般アプリのインストール。
    APP_INSTALLED,
    APP_MESSAGING_INSTALLED,
    APP_REMOTE_CONTROL_INSTALLED,
    APP_LAUNCHED_AFTER_INSTALL,
    NOTIFICATION_OBSERVED,
    CORRELATED_RISK,
    DEVICE_HEALTH,
}

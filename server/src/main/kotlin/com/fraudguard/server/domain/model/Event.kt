package com.fraudguard.server.domain.model

import kotlinx.serialization.Serializable

/** requirements.md 22章: イベント共通モデル。metadataはイベント種別ごとに使うキーが変わる。 */
@Serializable
data class EventMetadata(
    val phoneNumber: String? = null,
    val callId: String? = null,
    val direction: String? = null, // "INCOMING" | "OUTGOING"
    val durationSeconds: Long? = null,
    val packageName: String? = null,
    val appName: String? = null,
    val messageBody: String? = null,
    val sourceApp: String? = null,
    val reason: String? = null,
    val confidence: Double? = null,
)

@Serializable
data class Event(
    val eventId: String,
    val deviceId: String,
    val type: EventType,
    val riskLevel: RiskLevel,
    val title: String,
    val detail: String,
    val timestamp: String, // ISO-8601
    val metadata: EventMetadata = EventMetadata(),
    val acknowledged: Boolean = false,
    val createdAt: String? = null,
)

/** POST /events のリクエストボディ。Monitorアプリが送信するイベント登録要求。 */
@Serializable
data class CreateEventRequest(
    val eventId: String,
    val deviceId: String,
    val type: EventType,
    val riskLevel: RiskLevel,
    val title: String,
    val detail: String,
    val timestamp: String,
    val metadata: EventMetadata = EventMetadata(),
)

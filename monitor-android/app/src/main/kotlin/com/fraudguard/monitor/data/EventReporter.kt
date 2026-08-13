package com.fraudguard.monitor.data

import com.fraudguard.monitor.data.local.dao.EventDao
import com.fraudguard.monitor.data.local.entity.EventEntity
import com.fraudguard.monitor.data.remote.ApiClient
import com.fraudguard.monitor.data.remote.CreateEventRequest
import com.fraudguard.monitor.pairing.PairingRepository
import com.fraudguard.monitor.risk.EventMetadata
import com.fraudguard.monitor.risk.EventType
import com.fraudguard.monitor.risk.RiskLevel
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.json.Json

/**
 * requirements.md 22章, 24章: リスクイベントをローカルDBへ保存した上でサーバーへ送信する。
 * 通信失敗時もローカルには残るため、EventSyncWorkerが後から再送する(イベントロストしない)。
 * eventIdはここで採番し、サーバー側の冪等性(24章)のキーとなる。
 */
class EventReporter(
    private val eventDao: EventDao,
    private val pairingRepository: PairingRepository,
) {
    private val json = Json { encodeDefaults = true }

    suspend fun report(
        type: EventType,
        riskLevel: RiskLevel,
        title: String,
        detail: String,
        metadata: EventMetadata,
    ) {
        val eventId = UUID.randomUUID().toString()
        val now = Instant.now()
        val metadataJson = json.encodeToString(EventMetadata.serializer(), metadata)

        eventDao.insert(
            EventEntity(
                eventId = eventId,
                type = type,
                riskLevel = riskLevel,
                title = title,
                detail = detail,
                timestampMillis = now.toEpochMilli(),
                metadataJson = metadataJson,
                synced = false,
                createdAtMillis = now.toEpochMilli(),
            ),
        )

        // 即時送信を試みる。失敗してもローカルに残っているのでEventSyncWorkerが再送する。
        val deviceId = pairingRepository.getDeviceId() ?: return
        val apiKey = pairingRepository.getApiKey() ?: return
        runCatching {
            val response = ApiClient.create { apiKey }.postEvent(
                CreateEventRequest(
                    eventId = eventId,
                    deviceId = deviceId,
                    type = type,
                    riskLevel = riskLevel,
                    title = title,
                    detail = detail,
                    timestamp = now.toString(),
                    metadata = metadata,
                ),
            )
            if (response.isSuccessful) eventDao.markSynced(eventId)
        }
    }
}

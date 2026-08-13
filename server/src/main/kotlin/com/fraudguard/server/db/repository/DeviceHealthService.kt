package com.fraudguard.server.db.repository

import com.fraudguard.server.domain.model.CreateEventRequest
import com.fraudguard.server.domain.model.Event
import com.fraudguard.server.domain.model.EventType
import com.fraudguard.server.domain.model.RiskLevel
import com.fraudguard.server.notify.FamilyNotifierProvider
import java.time.Instant
import java.util.UUID

/**
 * requirements.md 35章[v2]: 監視継続性(自己監視)に関するdevice_healthイベントの生成と家族通知。
 * 詐欺犯が被害者に監視アプリの無効化を指示するケースを検知するための仕組みで、
 * (1) ハートビート受信時点での権限失効の即時検知(routes/HeartbeatRoutes.kt)と、
 * (2) ハートビート自体が途絶したことのタイムアウト検知(HeartbeatWatchdog)の両方から呼ばれる。
 */
object DeviceHealthService {

    // requirements.md 30章: 同じ状態が続く間、繰り返し通知しないための抑制期間。
    private const val DEDUPE_MINUTES = 360L // 6時間

    /** requirements.md 35.3章: 通知アクセス許可が(ハートビート受信時点で)失効している場合。 */
    suspend fun reportPermissionRevoked(deviceId: String) {
        createIfNotRecentlyReported(
            deviceId = deviceId,
            title = "通知アクセスの許可が無効になっています",
            detail = "監視端末で通知アクセスの許可が失われました。詐欺犯からの指示等でアプリの権限が無効化された可能性があります。",
        )
    }

    /** requirements.md 35.3, 35.4章: ハートビートが想定間隔を超えて途絶した場合(実質的なアンインストール検知も兼ねる)。 */
    suspend fun reportHeartbeatTimeout(deviceId: String, thresholdMinutes: Long) {
        val hours = thresholdMinutes / 60
        createIfNotRecentlyReported(
            deviceId = deviceId,
            title = "監視端末からの報告が途絶えています",
            detail = "${hours}時間以上、端末からの通信がありません。監視アプリが無効化・削除された可能性があります。",
        )
    }

    private suspend fun createIfNotRecentlyReported(deviceId: String, title: String, detail: String) {
        if (EventRepository.existsRecentOfType(deviceId, EventType.DEVICE_HEALTH, DEDUPE_MINUTES)) return

        val request = CreateEventRequest(
            eventId = UUID.randomUUID().toString(),
            deviceId = deviceId,
            type = EventType.DEVICE_HEALTH,
            riskLevel = RiskLevel.WARNING,
            title = title,
            detail = detail,
            timestamp = Instant.now().toString(),
        )
        val inserted = EventRepository.insertIfAbsent(request, deviceId)
        if (!inserted) return

        val deviceName = DeviceRepository.findName(deviceId) ?: deviceId
        val event = Event(
            eventId = request.eventId,
            deviceId = deviceId,
            type = request.type,
            riskLevel = request.riskLevel,
            title = request.title,
            detail = request.detail,
            timestamp = request.timestamp,
            metadata = request.metadata,
        )
        FamilyNotifierProvider.get().notify(event, deviceName)
    }
}

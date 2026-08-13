package com.fraudguard.server.db.repository

import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import org.slf4j.LoggerFactory

/**
 * requirements.md 35.3, 35.4章: サーバー側の死活監視。定期的に各端末の最終ハートビートを確認し、
 * 想定間隔を超えて途絶していれば device_health イベントを生成して家族へ通知する。
 * アプリが完全にアンインストールされた場合、これが唯一の検知手段となる(35.4章)。
 *
 * Application.kt から `application.launch { HeartbeatWatchdog.run() }` の形で、
 * アプリケーションのライフサイクルに紐づくコルーチンとして起動する。
 */
object HeartbeatWatchdog {
    private val logger = LoggerFactory.getLogger(HeartbeatWatchdog::class.java)

    // requirements.md 30章: 閾値は将来的に設定可能にする想定。現状はシナリオGの例に合わせた既定値。
    const val DEFAULT_TIMEOUT_MINUTES = 240L // 4時間
    private val CHECK_INTERVAL = 15.minutes

    suspend fun run(timeoutMinutes: Long = DEFAULT_TIMEOUT_MINUTES) {
        while (coroutineContext.isActive) {
            try {
                checkOnce(timeoutMinutes)
            } catch (e: Exception) {
                // DB未起動等、一時的な障害でウォッチドッグ自体を止めない。
                logger.warn("HeartbeatWatchdog check failed: ${e.message}")
            }
            delay(CHECK_INTERVAL)
        }
    }

    suspend fun checkOnce(timeoutMinutes: Long = DEFAULT_TIMEOUT_MINUTES) {
        val staleDeviceIds = DeviceRepository.findStaleDevices(timeoutMinutes)
        for (deviceId in staleDeviceIds) {
            DeviceHealthService.reportHeartbeatTimeout(deviceId, timeoutMinutes)
        }
    }
}

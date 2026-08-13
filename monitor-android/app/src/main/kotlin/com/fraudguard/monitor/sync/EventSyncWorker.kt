package com.fraudguard.monitor.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fraudguard.monitor.FraudGuardApplication
import com.fraudguard.monitor.data.remote.ApiClient
import com.fraudguard.monitor.data.remote.CreateEventRequest
import com.fraudguard.monitor.risk.EventMetadata
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json

/**
 * requirements.md 24章: オフライン対応。通信失敗時にイベントを消さず、
 * 復旧後にWorkManagerで再送する。eventIdの冪等性によりサーバー側で二重登録を防ぐ。
 */
class EventSyncWorker(private val appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val app = appContext.applicationContext as FraudGuardApplication
        val pairingRepository = app.pairingRepository
        val deviceId = pairingRepository.getDeviceId() ?: return Result.success() // 未ペアリングなら何もしない
        val apiKey = pairingRepository.getApiKey() ?: return Result.success()

        val eventDao = app.database.eventDao()
        val unsynced = eventDao.getUnsynced()
        if (unsynced.isEmpty()) return Result.success()

        val api = ApiClient.create { apiKey }
        var hadFailure = false

        for (event in unsynced) {
            val metadata = runCatching {
                json.decodeFromString(EventMetadata.serializer(), event.metadataJson)
            }.getOrDefault(EventMetadata())

            val result = runCatching {
                api.postEvent(
                    CreateEventRequest(
                        eventId = event.eventId,
                        deviceId = deviceId,
                        type = event.type,
                        riskLevel = event.riskLevel,
                        title = event.title,
                        detail = event.detail,
                        timestamp = Instant.ofEpochMilli(event.timestampMillis).toString(),
                        metadata = metadata,
                    ),
                )
            }
            if (result.isSuccess && result.getOrNull()?.isSuccessful == true) {
                eventDao.markSynced(event.eventId)
            } else {
                hadFailure = true
            }
        }

        // 一件でも失敗したらWorkManagerのバックオフに委ねて再試行する(イベントはローカルに残る)。
        return if (hadFailure) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "fraudguard-event-sync"

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<EventSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

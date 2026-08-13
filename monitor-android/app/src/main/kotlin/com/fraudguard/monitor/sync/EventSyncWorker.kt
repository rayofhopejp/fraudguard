package com.fraudguard.monitor.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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

        // requirements.md 11章: 新規アプリの検知。ブロードキャストが届かない端末でも取りこぼさないよう、
        // 定期スキャンを正の経路とする(検出したイベントはこの後の未送信分としてまとめて送られる)。
        runCatching { app.appInstallScanner.scan() }
        // requirements.md 13章: インストール済みアプリの初回起動検知。
        runCatching { app.appLaunchDetector.checkLaunches() }

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
            val response = result.getOrNull()
            when {
                result.isSuccess && response?.isSuccessful == true -> eventDao.markSynced(event.eventId)

                // requirements.md 24章: サーバーが「この内容は受け付けられない」と答えた場合、
                // 何度送り直しても結果は変わらない。再試行し続けると、そのイベントが詰まりとなって
                // 後続の正常なイベントまで永久に送れなくなる(1件の毒で全体が止まる)ため、
                // 送信済み扱いにしてキューを進める。認証切れ(401)とレート制限(429)は
                // 時間をおけば通るので除外する。
                response != null && response.code() in 400..499 &&
                    response.code() != 401 && response.code() != 408 && response.code() != 429 -> {
                    // requirements.md 25章: 電話番号やSMS本文は出さず、種別とコードのみ残す。
                    android.util.Log.w(
                        "FraudGuardSync",
                        "dropping event permanently rejected by server: type=${event.type} code=${response.code()}",
                    )
                    eventDao.markSynced(event.eventId)
                }

                else -> hadFailure = true
            }
        }

        // 通信不能・サーバー障害の場合のみ再試行する(イベントはローカルに残る)。
        return if (hadFailure) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "fraudguard-event-sync"
        private const val CATCH_UP_WORK_NAME = "fraudguard-event-sync-catch-up"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<EventSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build()

            // KEEPではなくUPDATEを使う。KEEPだと、WorkManagerのDB上はENQUEUEDのまま
            // JobSchedulerへの登録だけが失われた状態(強制停止・再インストール・OEMの省電力管理などで
            // 実際に起きる)から永久に復帰できない。既存の登録を尊重してしまい、二度と再登録されないため。
            // 実機で、未送信イベントが25分以上滞留し、EventSyncWorkerにジョブIDが割り当たっていない
            // 状態として発覚した(HeartbeatWorkerだけが登録されていた)。
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)

            // 定期実行は最短でも15分後で、しかも上記のように失われうる。起動のたびに一度だけ
            // 追い付き送信を投げ、溜まった未送信イベントが定期実行を待たずに出て行くようにする。
            WorkManager.getInstance(context).enqueueUniqueWork(
                CATCH_UP_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<EventSyncWorker>().setConstraints(constraints()).build(),
            )
        }
    }
}

package com.fraudguard.monitor.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * requirements.md 24章: オフライン対応。通信失敗時にイベントを消さず、
 * 復旧後にWorkManagerで再送する。eventIdの冪等性によりサーバー側で二重登録を防ぐ。
 *
 * TODO: AppDatabase.eventDao().getUnsynced() → ApiClient.postEvent() → markSynced()。
 *       送信成功のたびに次のイベントへ進み、失敗時はWorkManagerの自動リトライに委ねる。
 */
class EventSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return Result.success()
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

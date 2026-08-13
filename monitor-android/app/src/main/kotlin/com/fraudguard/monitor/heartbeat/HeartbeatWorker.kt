package com.fraudguard.monitor.heartbeat

import android.app.role.RoleManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
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
import com.fraudguard.monitor.data.remote.HeartbeatRequest
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * requirements.md 35章[v2]: 監視継続性のハートビート。30分〜1時間間隔で送信し、
 * 通知アクセス許可・ROLE_DIALER保持状態をサーバーへ報告する。
 * これにより「詐欺犯に監視アプリを止められた」ケースをサーバー側のタイムアウト検知で拾える(35.4章)。
 */
class HeartbeatWorker(private val appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val pairingRepository = (appContext.applicationContext as FraudGuardApplication).pairingRepository
        val deviceId = pairingRepository.getDeviceId() ?: return Result.success() // 未ペアリングなら何もしない
        val apiKey = pairingRepository.getApiKey() ?: return Result.success()

        val notificationListenerEnabled = NotificationManagerCompat.getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)
        val roleDialerHeld = appContext.getSystemService(RoleManager::class.java)
            ?.isRoleHeld(RoleManager.ROLE_DIALER) ?: false
        val appVersion = appContext.packageManager
            .getPackageInfo(appContext.packageName, 0).versionName ?: "unknown"

        val request = HeartbeatRequest(
            deviceId = deviceId,
            timestamp = Instant.now().toString(),
            notificationListenerEnabled = notificationListenerEnabled,
            roleDialerHeld = roleDialerHeld,
            appVersion = appVersion,
        )

        return try {
            val response = ApiClient.create { apiKey }.postHeartbeat(deviceId, request)
            if (response.isSuccessful) Result.success() else Result.retry()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "fraudguard-heartbeat"
        private const val IMMEDIATE_WORK_NAME = "fraudguard-heartbeat-now"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints())
                .build()

            // EventSyncWorkerと同じ理由でUPDATEを使う。KEEPだと、WorkManagerのDB上はENQUEUEDのまま
            // JobSchedulerへの登録だけが失われた状態から永久に復帰できない。
            // ハートビートが止まると家族には「監視が死んだ」ように見えるため、ここは特に効く。
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            sendNow(context)
        }

        /**
         * requirements.md 35章[v2]: いますぐ1回送る。
         *
         * 定期実行は最短でも30分後で、ペアリング直後は1件も届かない。
         * 家族の画面には「監視状態を確認できません」と出たままになり、
         * 設置作業をしたその場で見守りが機能していることを確認できない。
         */
        fun sendNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<HeartbeatWorker>().setConstraints(constraints()).build(),
            )
        }
    }
}

package com.fraudguard.monitor.call

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import com.fraudguard.monitor.FraudGuardApplication
import com.fraudguard.monitor.risk.EventMetadata
import com.fraudguard.monitor.risk.EventType
import com.fraudguard.monitor.risk.RiskLevel
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * requirements.md 4.3章[v2]: ROLE_DIALERを取らずに通話を監視する軽量版。
 *
 * デフォルト電話アプリの入れ替えは、その端末の利用者にとって影響が大きい
 * (らくらくスマートフォンのように、標準の電話アプリが高齢者向けに作られている端末では特に)。
 * 電話アプリを置き換えずに「知らない番号との通話が続いている」ことだけでも家族へ届けば、
 * 家族が本人に電話をかけて我に返らせる、という介入はできる。
 *
 * ROLE_DIALER保持時はFraudGuardInCallServiceが同じ通話を報告するため、このサービスは動かない
 * (同じ通話が2件report されるのを防ぐ)。
 *
 * **番号が取れないことがある。** Android 12以降、TelephonyCallbackは通話状態しか渡さない。
 * PHONE_STATEブロードキャストのEXTRA_INCOMING_NUMBERはREAD_CALL_LOG保持時のみ値が入るが、
 * 端末やバージョンによっては空になる。発信番号にいたっては取得手段が無い
 * (PROCESS_OUTGOING_CALLSはAndroid 10で廃止)。
 * 番号が取れない場合も通話自体は報告する。番号が無いと7章の番号判定はできないが、
 * 7.4章の通話時間の警告は成立し、それがこの構成での主な価値になる。
 */
class CallMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "fraudguard_monitoring"
        private const val NOTIFICATION_ID = 1

        /** 番号が届くのを待つ上限(200ms × 15 = 3秒)。着信中に収まる長さ。 */
        private const val PHONE_NUMBER_WAIT_ATTEMPTS = 15
        private const val PHONE_NUMBER_WAIT_INTERVAL_MS = 200L

        /** requirements.md 8.1章: 「対象通話が現在ACTIVEであること」の検証にRemoteCommandExecutorが使う。 */
        @Volatile
        var activeCallId: String? = null
            private set

        /**
         * requirements.md 4.3章[v2]: ROLE_DIALERを持っていない場合のみ起動する。
         * 保持時はFraudGuardInCallServiceが通話を見るため、起こすと二重報告になる。
         */
        fun startIfDialerRoleNotHeld(context: Context) {
            val app = context.applicationContext as? FraudGuardApplication ?: return
            if (!app.pairingRepository.isPaired()) return
            if (context.getSystemService<RoleManager>()?.isRoleHeld(RoleManager.ROLE_DIALER) == true) return
            // Android 12以降、バックグラウンドからのフォアグラウンドサービス起動は例外になる。
            // 呼び出し元(画面表示・BOOT_COMPLETED)は許可される経路だが、
            // 監視の起動処理そのものでアプリを落とすわけにはいかないので保険をかける。
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    Intent(context, CallMonitorService::class.java),
                )
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob())
    private var currentCallId: String? = null
    private var currentPhoneNumber: String? = null
    private var lastState: Int = TelephonyManager.CALL_STATE_IDLE
    private var durationJob: Job? = null

    /** onStartCommandは起動要求のたびに呼ばれる。監視の登録は一度だけにする(二重登録を防ぐ)。 */
    private var listening = false

    /**
     * 着信番号はPHONE_STATEブロードキャストからしか得られない。
     * 状態変化(TelephonyCallback)より先に届くとは限らないため、届いた値を保持しておき
     * 通話イベントを組み立てるときに参照する。
     */
    private val phoneStateReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context?, intent: Intent?) {
            val number = intent?.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (!number.isNullOrBlank()) currentPhoneNumber = number
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService()で起動された場合、ここで即座にstartForeground()を呼ばないと
        // ForegroundServiceDidNotStartInTimeExceptionでアプリごとクラッシュする(実機で発生)。
        // 常駐通知はrequirements.md 26章の透明性(監視されていることが本人に分かる)にも資する。
        //
        // requirements.md 30章: 起動できない状況(端末やOSの制限)でも、監視の起動処理そのもので
        // アプリを落としてはならない。落ちれば他の監視まで巻き添えで止まる。
        // 実際にphoneCall種別で起動を試みてSecurityExceptionでプロセスごと落ちた。
        val started = runCatching { startForeground(NOTIFICATION_ID, buildMonitoringNotification()) }
        if (started.isFailure) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 起動後にデフォルト電話アプリになった場合、こちらは退く(二重報告を避ける)。
        if (getSystemService<RoleManager>()?.isRoleHeld(RoleManager.ROLE_DIALER) == true) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (listening) return START_STICKY
        val telephonyManager = getSystemService<TelephonyManager>() ?: return START_STICKY

        registerReceiver(phoneStateReceiver, IntentFilter("android.intent.action.PHONE_STATE"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.registerTelephonyCallback(mainExecutor, callback31)
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
        listening = true
        return START_STICKY
    }

    override fun onDestroy() {
        if (listening) runCatching { unregisterReceiver(phoneStateReceiver) }
        runCatching {
            val telephonyManager = getSystemService<TelephonyManager>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyManager?.unregisterTelephonyCallback(callback31)
            } else {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(legacyListener, PhoneStateListener.LISTEN_NONE)
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildMonitoringNotification(): Notification {
        val manager = getSystemService<NotificationManager>()
        if (manager?.getNotificationChannel(CHANNEL_ID) == null) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "監視状態", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "FraudGuardが通話を監視していることを示す常駐通知です。"
                },
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FraudGuard 監視中")
            .setContentText("不審な電話やSMSを監視しています。")
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setOngoing(true)
            .build()
    }

    private val callback31 = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = handleStateChange(state)
    }

    @Suppress("DEPRECATION")
    private val legacyListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            if (!phoneNumber.isNullOrBlank()) currentPhoneNumber = phoneNumber
            handleStateChange(state)
        }
    }

    /**
     * requirements.md 4.2章: 着信/発信/通話中/終了の遷移を追う。
     *
     * 着信は RINGING → OFFHOOK(応答) → IDLE、発信は IDLE → OFFHOOK → IDLE と遷移する。
     * RINGINGを経ずにOFFHOOKへ入ったかどうかが、着信と発信を見分ける唯一の手がかりになる。
     */
    private fun handleStateChange(state: Int) {
        // 起動後にデフォルト電話アプリになった場合、FraudGuardInCallServiceが同じ通話を報告する。
        // 起動時の確認だけでは、既に走っているこのサービスが残り続けて二重報告になる
        // (実機で、同じ着信が別々のcallIdで2件報告され、しかも片方が古い番号を載せていた)。
        if (getSystemService<RoleManager>()?.isRoleHeld(RoleManager.ROLE_DIALER) == true) {
            stopSelf()
            return
        }

        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                currentCallId = UUID.randomUUID().toString()
                reportCall(EventType.CALL_INCOMING, "着信", "INCOMING")
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // RINGINGを経ていなければ発信。着信の応答ならここでは報告済み。
                if (lastState != TelephonyManager.CALL_STATE_RINGING) {
                    currentCallId = UUID.randomUUID().toString()
                    reportCall(EventType.CALL_OUTGOING, "発信", "OUTGOING")
                }
                activeCallId = currentCallId
                startDurationTracking()
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                durationJob?.cancel()
                durationJob = null
                currentCallId = null
                currentPhoneNumber = null
                activeCallId = null
            }
        }
        lastState = state
    }

    private fun reportCall(type: EventType, title: String, direction: String) {
        val app = applicationContext as? FraudGuardApplication ?: return
        val callId = currentCallId ?: return

        serviceScope.launch {
            // 番号はPHONE_STATEブロードキャスト経由で届くが、通話状態の変化より遅れて着くことがある
            // (実機では、着信の報告時には番号が無く、応答後には取れていた)。
            // 相手が誰かは判定の根幹なので、短時間だけ待つ。待っても来なければ番号なしで報告する
            // (通話があったこと自体と、7.4章の通話時間の警告は番号が無くても成立する)。
            val phoneNumber = awaitPhoneNumber(callId)
            app.eventReporter.report(
                type = type,
                riskLevel = RiskLevel.NOTICE, // 最終的なリスク判定はサーバー側RiskEngineが行う
                title = title,
                detail = if (phoneNumber == null) {
                    "通話を検知しました(相手の番号は取得できませんでした)。"
                } else {
                    "通話を検知しました。"
                },
                metadata = EventMetadata(
                    phoneNumber = phoneNumber,
                    callId = callId,
                    direction = direction,
                ),
            )
        }
    }

    /**
     * 番号が届くのを短時間だけ待つ。通話が終わってしまった場合はそこで打ち切る。
     * 発信では番号を取得する手段が無いため(PROCESS_OUTGOING_CALLSはAndroid 10で廃止)、
     * 必ず待ち時間いっぱいまで待ってnullになる。ここは待たせても実害が無い範囲に留める。
     */
    private suspend fun awaitPhoneNumber(callId: String): String? {
        repeat(PHONE_NUMBER_WAIT_ATTEMPTS) {
            currentPhoneNumber?.let { return it }
            if (currentCallId != callId) return null
            delay(PHONE_NUMBER_WAIT_INTERVAL_MS)
        }
        return currentPhoneNumber
    }

    /**
     * requirements.md 7.4章: 通話時間の警告。デフォルト電話アプリを取らない構成では、
     * これがこのサービスの主な価値になる(番号が取れない場合でも成立するため)。
     */
    private fun startDurationTracking() {
        val app = applicationContext as? FraudGuardApplication ?: return
        val callId = currentCallId ?: return
        val phoneNumber = currentPhoneNumber
        val startedAtMillis = System.currentTimeMillis()

        durationJob?.cancel()
        durationJob = serviceScope.launch {
            for (threshold in longCallThresholdsSeconds(
                includeFirstMinute = true,
                foreign = isLikelyForeignNumber(phoneNumber),
            )) {
                val remaining = threshold - (System.currentTimeMillis() - startedAtMillis) / 1000
                if (remaining <= 0) continue
                delay(remaining * 1000)
                // 通話が既に終わっていたら報告しない。
                if (currentCallId != callId) return@launch

                app.eventReporter.report(
                    type = EventType.CALL_LONG_DURATION,
                    riskLevel = RiskLevel.NOTICE,
                    title = "通話が${threshold / 60}分を超えました",
                    detail = "通話が継続しています。",
                    metadata = EventMetadata(
                        phoneNumber = phoneNumber,
                        callId = callId,
                        durationSeconds = threshold,
                    ),
                )
            }
        }
    }
}

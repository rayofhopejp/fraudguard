package com.fraudguard.monitor.call

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.getSystemService
import java.util.UUID

/**
 * requirements.md 4.3章[v2]: 通話「監視」はROLE_DIALER不要。TelephonyManagerの状態変化を
 * READ_PHONE_STATE権限のみで監視し、着信/発信/通話時間を取得する(遠隔切断は別フェーズ、command/参照)。
 *
 * API 31+ は TelephonyCallback、API 29-30 は非推奨の PhoneStateListener にフォールバックする。
 *
 * TODO: 通話開始(ACTIVE)時刻からの経過時間計測 → requirements.md 7.4章の180秒/300秒/600秒ルールの発火、
 *       PhoneNumberClassifier + ホワイトリストキャッシュでのリスク判定、EventRepositoryへの記録。
 */
class CallMonitorService : Service() {

    companion object {
        /** requirements.md 8.1章: 「対象通話が現在ACTIVEであること」の検証にRemoteCommandExecutorが使う。 */
        @Volatile
        var activeCallId: String? = null
            private set
    }

    private var currentCallId: String? = null
    private var activeStartedAtMillis: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val telephonyManager = getSystemService<TelephonyManager>() ?: return START_STICKY

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            telephonyManager.registerTelephonyCallback(mainExecutor, callback31)
        } else {
            @Suppress("DEPRECATION")
            telephonyManager.listen(legacyListener, PhoneStateListener.LISTEN_CALL_STATE)
        }
        return START_STICKY
    }

    private val callback31 = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = handleStateChange(state)
    }

    @Suppress("DEPRECATION")
    private val legacyListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleStateChange(state)
    }

    private fun handleStateChange(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                currentCallId = UUID.randomUUID().toString()
                // TODO: 着信番号取得(READ_CALL_LOG or EXTRA_INCOMING_NUMBER)、Classification、CALL_INCOMING イベント生成
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                activeStartedAtMillis = System.currentTimeMillis()
                activeCallId = currentCallId
                // TODO: 通話時間のリアルタイム計測開始(requirements.md 4.2章)
                // TODO: 発信(OUTGOING)はRINGINGを経ないため、この時点でcurrentCallIdが未採番。
                //       発信検知(NEW_OUTGOING_CALLの後継APIまたはCALL_STATE遷移パターン)を別途実装する必要がある。
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                activeStartedAtMillis = null
                currentCallId = null
                activeCallId = null
                // TODO: 通話終了イベント・最終的な通話時間を確定してサーバーへ送信
            }
        }
    }
}

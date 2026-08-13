package com.fraudguard.monitor.call

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fraudguard.monitor.ui.incall.InCallScreen
import com.fraudguard.monitor.ui.theme.FraudGuardMonitorTheme

/**
 * requirements.md 4.3章[v2]: FraudGuardInCallService.onCallAddedが起動する、着信/通話中の自前UI。
 * IN_CALL_SERVICE_UI=trueによりシステム標準UIの代わりとなるため、この画面が無いと
 * デフォルト電話アプリ化後に着信・通話中の状態が一切見えなくなってしまう。
 */
class InCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FraudGuardMonitorTheme {
                val calls by FraudGuardInCallService.calls.collectAsState()
                val primaryCall = calls.firstOrNull()

                // 通話が無くなったら(切断・終了)自動的に画面を閉じる。
                LaunchedEffect(primaryCall) {
                    if (primaryCall == null) finish()
                }

                primaryCall?.let { call ->
                    InCallScreen(
                        call = call,
                        onAnswer = { FraudGuardInCallService.instance?.answer(call.callId) },
                        onReject = { FraudGuardInCallService.instance?.reject(call.callId) },
                        onHangup = { FraudGuardInCallService.instance?.disconnect(call.callId) },
                    )
                }
            }
        }
    }
}

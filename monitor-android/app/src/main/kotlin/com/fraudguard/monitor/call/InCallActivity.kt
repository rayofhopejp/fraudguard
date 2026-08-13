package com.fraudguard.monitor.call

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.fraudguard.monitor.FraudGuardApplication
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
        val app = application as FraudGuardApplication

        setContent {
            FraudGuardMonitorTheme {
                val calls by FraudGuardInCallService.calls.collectAsState()
                // requirements.md 10.3章: LINE等のアプリ内通話はアプリ側が通話UIを持つため、
                // この画面には出さない(出すとLINEの通話画面を覆ってしまう)。
                val primaryCall = calls.firstOrNull { !it.isSelfManaged }

                // 通話が無くなったら(切断・終了)自動的に画面を閉じる。
                LaunchedEffect(primaryCall) {
                    if (primaryCall == null) finish()
                }

                primaryCall?.let { call ->
                    // 相手の解決は連絡先とホワイトリストへの問い合わせを伴うので、
                    // 画面表示を待たせないよう非同期で解決し、判明した時点で差し替える。
                    val identity by produceState(
                        initialValue = CallerIdentity(
                            phoneNumber = call.phoneNumber,
                            displayName = null,
                            isWhitelisted = false,
                            source = CallerIdentity.Source.UNKNOWN,
                        ),
                        key1 = call.phoneNumber,
                    ) {
                        value = app.callerIdentityResolver.resolve(call.phoneNumber)
                    }

                    var isMuted by remember { mutableStateOf(false) }
                    var isSpeakerOn by remember { mutableStateOf(false) }

                    InCallScreen(
                        call = call,
                        identity = identity,
                        onAnswer = { FraudGuardInCallService.instance?.answer(call.callId) },
                        onReject = { FraudGuardInCallService.instance?.reject(call.callId) },
                        onHangup = { FraudGuardInCallService.instance?.disconnect(call.callId) },
                        isMuted = isMuted,
                        isSpeakerOn = isSpeakerOn,
                        onToggleMute = {
                            isMuted = !isMuted
                            FraudGuardInCallService.instance?.applyMuted(isMuted)
                        },
                        onToggleSpeaker = {
                            isSpeakerOn = !isSpeakerOn
                            FraudGuardInCallService.instance?.applySpeaker(isSpeakerOn)
                        },
                    )
                }
            }
        }
    }
}

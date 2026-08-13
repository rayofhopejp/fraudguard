package com.fraudguard.monitor.ui.incall

import android.telecom.Call
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fraudguard.monitor.call.CallerIdentity
import com.fraudguard.monitor.call.TrackedCall

/**
 * requirements.md 4.3章[v2]: ROLE_DIALER化により、システム標準の着信/通話中UIの代わりに
 * このアプリが表示する画面。
 *
 * この端末の利用者は高齢者を想定しているため、次を優先している。
 *  - 相手が誰かを最初に大きく出す(番号だけでは判断できない)
 *  - 登録済みの相手か、知らない番号かをその場で示す(7章の判定を本人にも伝える)
 *  - 応答/拒否は取り違えようのない大きさに分ける(押し間違いは実害に直結する)
 */
@Composable
fun InCallScreen(
    call: TrackedCall,
    identity: CallerIdentity,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onHangup: () -> Unit,
    isMuted: Boolean = false,
    isSpeakerOn: Boolean = false,
    onToggleMute: () -> Unit = {},
    onToggleSpeaker: () -> Unit = {},
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = callStateLabel(call.state), fontSize = 18.sp)

                Text(
                    text = identity.primaryLabel,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp),
                )
                identity.secondaryLabel?.let {
                    Text(text = it, fontSize = 20.sp, modifier = Modifier.padding(top = 4.dp))
                }

                Text(
                    text = identity.trustLabel,
                    fontSize = 18.sp,
                    fontWeight = if (identity.isTrusted) FontWeight.Normal else FontWeight.Bold,
                    color = if (identity.isTrusted) MaterialTheme.colorScheme.onSurfaceVariant else UNKNOWN_CALLER_COLOR,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp),
                )
            }

            when (call.state) {
                Call.STATE_RINGING -> IncomingCallControls(onAnswer = onAnswer, onReject = onReject)
                Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> Text(text = "通話終了", fontSize = 20.sp)
                else -> ActiveCallControls(
                    isMuted = isMuted,
                    isSpeakerOn = isSpeakerOn,
                    onToggleMute = onToggleMute,
                    onToggleSpeaker = onToggleSpeaker,
                    onHangup = onHangup,
                )
            }
        }
    }
}

/** 応答と拒否は縦に離して大きく置く。横並びの小さなボタンは押し間違えやすい。 */
@Composable
private fun IncomingCallControls(onAnswer: () -> Unit, onReject: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(
            onClick = onAnswer,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ANSWER_COLOR),
        ) {
            Text("応答する", fontSize = 24.sp)
        }
        OutlinedButton(
            onClick = onReject,
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) {
            Text("拒否する", fontSize = 24.sp)
        }
    }
}

@Composable
private fun ActiveCallControls(
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onHangup: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 通話中に音声の切り替えができないと、通話が成立しない場面がある(耳が遠い場合のスピーカー等)。
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(
                onClick = onToggleMute,
                modifier = Modifier.weight(1f).height(64.dp),
            ) {
                Text(if (isMuted) "ミュート中" else "ミュート", fontSize = 18.sp)
            }
            FilledTonalButton(
                onClick = onToggleSpeaker,
                modifier = Modifier.weight(1f).height(64.dp),
            ) {
                Text(if (isSpeakerOn) "スピーカー中" else "スピーカー", fontSize = 18.sp)
            }
        }
        Button(
            onClick = onHangup,
            modifier = Modifier.fillMaxWidth().height(72.dp),
            colors = ButtonDefaults.buttonColors(containerColor = HANGUP_COLOR),
        ) {
            Text("通話を終了", fontSize = 24.sp)
        }
    }
}

private val ANSWER_COLOR = Color(0xFF2E7D32)
private val HANGUP_COLOR = Color(0xFFC62828)
private val UNKNOWN_CALLER_COLOR = Color(0xFFB71C1C)

/**
 * android.telecom.Call.STATE_* を日本語表示に変換する。
 * Android実機/Call型に依存しない純粋関数として、単体テストで境界値を検証する。
 */
fun callStateLabel(state: Int): String = when (state) {
    Call.STATE_RINGING -> "着信中"
    Call.STATE_DIALING -> "発信中"
    Call.STATE_ACTIVE -> "通話中"
    Call.STATE_HOLDING -> "保留中"
    Call.STATE_CONNECTING -> "接続中"
    Call.STATE_DISCONNECTED -> "通話終了"
    Call.STATE_DISCONNECTING -> "切断中"
    else -> "通話"
}

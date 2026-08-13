package com.fraudguard.monitor.ui.incall

import android.telecom.Call
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fraudguard.monitor.call.TrackedCall

/**
 * requirements.md 4.3章[v2]: ROLE_DIALER化により、システム標準の着信/通話中UIの代わりに
 * このアプリが表示する必要がある画面。単なる要件充足ではなく、実際に応答・拒否・終話ができる。
 */
@Composable
fun InCallScreen(call: TrackedCall, onAnswer: () -> Unit, onReject: () -> Unit, onHangup: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = callStateLabel(call.state), fontSize = 16.sp)
                Text(
                    text = call.phoneNumber ?: "非通知",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            when (call.state) {
                Call.STATE_RINGING -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        OutlinedButton(onClick = onReject) { Text("拒否") }
                        Button(onClick = onAnswer) { Text("応答") }
                    }
                }
                Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                    Text(text = "通話終了")
                }
                else -> {
                    Button(onClick = onHangup) { Text("通話を終了") }
                }
            }
        }
    }
}

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
